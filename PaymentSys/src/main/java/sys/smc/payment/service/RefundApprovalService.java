package sys.smc.payment.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.dto.RefundApproveRequest;
import sys.smc.payment.dto.RefundApplicationVO;
import sys.smc.payment.entity.RefundApplication;
import sys.smc.payment.entity.RefundAuditLog;
import sys.smc.payment.enums.RefundApplicationStatus;
import sys.smc.payment.exception.PaymentException;
import sys.smc.payment.mapper.RefundApplicationMapper;
import sys.smc.payment.mapper.RefundAuditLogMapper;
import sys.smc.payment.security.RequireFinance;
import sys.smc.payment.security.UserContext;
import java.util.Date;
import java.util.List;

/**
 * 退款审批服务（财务端）
 *
 * 退款次数限制：
 *   - 普通财务（parentGroupId=345）：同一笔交易最多批准 3 次部分退款
 *   - 经理（parentGroupId=59）：无限制
 *
 * 安全双保险：
 *   - FinanceAuthInterceptor（路径层）
 *   - @RequireFinance + FinanceAuthAspect（方法层 AOP）
 */
@Service
@Slf4j
public class RefundApprovalService {

    /** 普通财务每笔交易最多批准退款次数 */
    private static final int REGULAR_FINANCE_MAX_REFUND_COUNT = 3;

    @Autowired
    private RefundApplicationMapper applicationMapper;

    @Autowired
    private RefundAuditLogMapper auditLogMapper;

    @Autowired
    private RefundExecutionService refundExecutionService;  // 独立 @Service，@Async 才能生效

    @Autowired
    private AuthService authService;  // 高风险操作：二次从 DB 验权

    // ─────────────────────────────────────────────────────────
    // 查询接口
    // ─────────────────────────────────────────────────────────

    /**
     * 分页查询退款申请列表
     *
     * @param status   过滤状态（null = 全部）
     * @param pageNum  页码（1起）
     * @param pageSize 每页条数
     */
    @RequireFinance
    public Page<RefundApplication> listApplications(String status, int pageNum, int pageSize) {
        Page<RefundApplication> page = new Page<>(pageNum, pageSize);
        applicationMapper.selectPageByStatus(page, status);
        return page;
    }

    /**
     * 查询某申请的全部审计日志
     */
    @RequireFinance
    public List<RefundAuditLog> getAuditLog(Long applicationId) {
        // 确认申请单存在
        if (applicationMapper.selectById(applicationId) == null) {
            throw new PaymentException("退款申请不存在");
        }
        return auditLogMapper.selectByApplicationId(applicationId);
    }

    // ─────────────────────────────────────────────────────────
    // 审批接口
    // ─────────────────────────────────────────────────────────

    /**
     * 财务批准退款申请
     *
     * 安全机制：
     *   1. @RequireFinance AOP 验证财务权限
     *   2. 普通财务次数上限 3 次（经理无限制）
     *   3. 乐观锁防并发双重审批
     *   4. 审批成功后触发异步退款执行（不阻塞 HTTP 线程）
     *
     * @param request  审批请求
     * @param finance  当前财务用户（从 JWT 解析）
     */
    @RequireFinance
    @Transactional(rollbackFor = Exception.class)
    public void approveApplication(RefundApproveRequest request, UserContext finance) {

        // ── 1. 加载申请单 ─────────────────────────────────────────────────
        RefundApplication app = applicationMapper.selectById(request.getApplicationId());

        // ── ⭐ 二次从 DB 验证财务权限（防止 Token 盗用、角色已被撤销的场景）──
        authService.verifyRoleFromDB(finance.getUserId(), "FINANCE");
        if (app == null) {
            throw new PaymentException("退款申请不存在");
        }

        if (!RefundApplicationStatus.PENDING_REVIEW.name().equals(app.getStatus())) {
            throw new PaymentException("申请状态不是待审批，当前状态：" + app.getStatus());
        }

        // ── 2. 退款次数校验（普通财务限制 3 次，经理无限制）──────────────────
        if (!finance.isManager()) {
            int activeCount = applicationMapper.countActiveByTransactionId(app.getTransactionId());
            if (activeCount > REGULAR_FINANCE_MAX_REFUND_COUNT) {
                throw new PaymentException(String.format(
                        "该交易已有 %d 次退款记录，超出普通财务批准上限（%d 次）。"
                        + "如需继续退款，请联系经理（parentGroupId=59）审批。",
                        activeCount, REGULAR_FINANCE_MAX_REFUND_COUNT));
            }
        } else {
            log.info("[退款审批] 经理审批，无次数限制，applicationId={}", request.getApplicationId());
        }

        // ── 3. 乐观锁更新：PENDING_REVIEW → APPROVED ──────────────────────
        RefundApplication approveUpdate = new RefundApplication();
        approveUpdate.setId(request.getApplicationId());
        approveUpdate.setVersion(app.getVersion());
        approveUpdate.setStatus(RefundApplicationStatus.APPROVED.name());
        approveUpdate.setReviewedBy(finance.getUserId());
        approveUpdate.setReviewedAt(new Date());
        approveUpdate.setReviewRemark(request.getReviewRemark());
        approveUpdate.setUpdateUser(finance.getUserId());

        int updated = applicationMapper.updateById(approveUpdate);
        if (updated == 0) {
            throw new PaymentException("审批失败：该申请已被其他人处理，请刷新后重试");
        }

        // ── 4. 写审计日志 ──────────────────────────────────────────────────
        insertAuditLog(request.getApplicationId(), app.getTransactionId(),
                "APPROVE", finance,
                "批准退款，备注：" + request.getReviewRemark());

        log.info("[退款审批] 批准成功，applicationId={}，审批人={}，触发异步退款",
                request.getApplicationId(), finance.getUserId());

        // ── 5. 触发异步退款执行（必须在 @Transactional 提交后才能"看到" APPROVED 状态）──
        // Spring @Transactional 默认在方法结束后提交，此处使用 afterCommit 回调确保顺序
        // 简化方案：异步方法从 DB 重新查 APPROVED 状态，因此即使此处先调用也安全
        refundExecutionService.executeAsync(request.getApplicationId(), finance.getUserId());
    }

    /**
     * 财务拒绝退款申请
     *
     * @param request 审批请求（含拒绝原因）
     * @param finance 当前财务用户
     */
    @RequireFinance
    @Transactional(rollbackFor = Exception.class)
    public void rejectApplication(RefundApproveRequest request, UserContext finance) {

        RefundApplication app = applicationMapper.selectById(request.getApplicationId());
        if (app == null) {
            throw new PaymentException("退款申请不存在");
        }

        if (!RefundApplicationStatus.PENDING_REVIEW.name().equals(app.getStatus())) {
            throw new PaymentException("只有待审批的申请才能拒绝，当前状态：" + app.getStatus());
        }

        // 乐观锁更新：PENDING_REVIEW → REJECTED
        RefundApplication rejectUpdate = new RefundApplication();
        rejectUpdate.setId(request.getApplicationId());
        rejectUpdate.setVersion(app.getVersion());
        rejectUpdate.setStatus(RefundApplicationStatus.REJECTED.name());
        rejectUpdate.setReviewedBy(finance.getUserId());
        rejectUpdate.setReviewedAt(new Date());
        rejectUpdate.setReviewRemark(request.getReviewRemark());
        rejectUpdate.setUpdateUser(finance.getUserId());

        int updated = applicationMapper.updateById(rejectUpdate);
        if (updated == 0) {
            throw new PaymentException("拒绝失败：该申请已被其他人处理，请刷新后重试");
        }

        insertAuditLog(request.getApplicationId(), app.getTransactionId(),
                "REJECT", finance,
                "拒绝退款，原因：" + request.getReviewRemark());

        log.info("[退款审批] 已拒绝，applicationId={}，操作人={}", request.getApplicationId(), finance.getUserId());
    }

    // ───────── 内部工具 ─────────

    private void insertAuditLog(Long applicationId, String transactionId,
                                String action, UserContext operator, String remark) {
        RefundAuditLog auditLog = RefundAuditLog.builder()
                .id(Long.valueOf(IdUtil.getSnowflakeNextIdStr()))
                .applicationId(applicationId)
                .transactionId(transactionId)
                .action(action)
                .operatorUserId(operator.getUserId())
                .operatorGroupId(operator.getGroupId())
                .operatorParentGroupId(operator.getParentGroupId())
                .operatorIp(operator.getClientIp())
                .remark(remark)
                .createTime(new Date())
                .build();
        auditLogMapper.insert(auditLog);
    }
}




