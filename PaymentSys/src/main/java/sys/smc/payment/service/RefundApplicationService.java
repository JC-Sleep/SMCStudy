package sys.smc.payment.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.dto.RefundApplyRequest;
import sys.smc.payment.dto.RefundApplicationVO;
import sys.smc.payment.entity.RefundApplication;
import sys.smc.payment.entity.RefundAuditLog;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.RefundApplicationStatus;
import sys.smc.payment.exception.PaymentException;
import sys.smc.payment.mapper.PaymentTransactionMapper;
import sys.smc.payment.mapper.RefundApplicationMapper;
import sys.smc.payment.mapper.RefundAuditLogMapper;
import sys.smc.payment.security.UserContext;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 退款申请服务（用户端）
 *
 * 安全校验：
 *   1. 原交易必须 SUCCESS 或 PARTIALLY_REFUNDED
 *   2. 不能存在进行中的申请（PENDING_REVIEW/APPROVED/EXECUTING）
 *   3. 累计有效退款金额 + 本次申请 <= 原始金额
 *   4. 普通财务批准次数上限 3 次（此处仅做申请计数预警，实际上限在审批方）
 */
@Service
@Slf4j
public class RefundApplicationService {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private RefundApplicationMapper applicationMapper;

    @Autowired
    private RefundAuditLogMapper auditLogMapper;

    /**
     * 用户提交退款申请
     *
     * @param request   退款申请请求
     * @param applicant 申请人（从 JWT 解析的 UserContext）
     * @return 申请单ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long applyRefund(RefundApplyRequest request, UserContext applicant) {

        // ── 1. 查原交易 ────────────────────────────────────────────────────
        PaymentTransaction txn = transactionMapper.selectOne(
                new LambdaQueryWrapper<PaymentTransaction>()
                        .eq(PaymentTransaction::getTransactionId, request.getTransactionId()));

        if (txn == null) {
            throw new PaymentException("原交易不存在");
        }

        String status = txn.getPaymentStatus();
        if (!"SUCCESS".equals(status) && !"PARTIALLY_REFUNDED".equals(status)) {
            throw new PaymentException("该订单状态不允许退款，当前状态：" + status);
        }

        // ── 2. 防止并行重复提交（已有进行中的申请时拒绝）────────────────────
        int inProgress = applicationMapper.countInProgressByTransactionId(request.getTransactionId());
        if (inProgress > 0) {
            throw new PaymentException("该交易已有退款申请正在处理中，请等待结果后再提交");
        }

        // ── 3. 金额校验：累计有效退款 + 本次 <= 原始金额 ─────────────────────
        BigDecimal alreadyApplied = applicationMapper.sumActiveAmountByTransactionId(request.getTransactionId());
        if (alreadyApplied == null) alreadyApplied = BigDecimal.ZERO;

        BigDecimal totalAfterThis = alreadyApplied.add(request.getRefundAmount());
        if (totalAfterThis.compareTo(txn.getAmount()) > 0) {
            throw new PaymentException(String.format(
                    "退款金额超出上限。原始金额：%.2f，已申请：%.2f，本次申请：%.2f，剩余可退：%.2f",
                    txn.getAmount(), alreadyApplied, request.getRefundAmount(),
                    txn.getAmount().subtract(alreadyApplied)));
        }

        // ── 4. 创建申请记录 ───────────────────────────────────────────────
        RefundApplication application = new RefundApplication();
        application.setId(Long.valueOf(IdUtil.getSnowflakeNextIdStr()));
        application.setTransactionId(request.getTransactionId());
        application.setOrderReference(txn.getOrderReference());
        application.setGatewayCode(txn.getGatewayName());
        application.setRefundAmount(request.getRefundAmount());
        application.setOriginalAmount(txn.getAmount());
        application.setApplicantUserId(applicant.getUserId());
        application.setRefundReason(request.getRefundReason());
        application.setStatus(RefundApplicationStatus.PENDING_REVIEW.name());
        application.setCreateUser(applicant.getUserId());
        application.setUpdateUser(applicant.getUserId());
        applicationMapper.insert(application);

        // ── 5. 写审计日志 ──────────────────────────────────────────────────
        insertAuditLog(application.getId(), request.getTransactionId(),
                "APPLY", applicant,
                String.format("申请退款 %.2f，原因：%s", request.getRefundAmount(), request.getRefundReason()));

        log.info("退款申请已提交：applicationId={}，transactionId={}，amount={}，applicant={}",
                application.getId(), request.getTransactionId(),
                request.getRefundAmount(), applicant.getUserId());

        return application.getId();
    }

    /**
     * 用户查询自己的退款申请状态
     */
    public RefundApplicationVO getApplicationStatus(Long applicationId, String applicantUserId) {
        RefundApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            throw new PaymentException("退款申请不存在");
        }
        // 只能查自己的申请
        if (!app.getApplicantUserId().equals(applicantUserId)) {
            throw new PaymentException("无权查询此申请");
        }
        return toVO(app);
    }

    // ───────── 内部工具 ─────────

    private void insertAuditLog(Long applicationId, String transactionId,
                                String action, UserContext operator, String remark) {
        RefundAuditLog log = RefundAuditLog.builder()
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
        auditLogMapper.insert(log);
    }

    private RefundApplicationVO toVO(RefundApplication app) {
        return RefundApplicationVO.builder()
                .applicationId(app.getId())
                .transactionId(app.getTransactionId())
                .orderReference(app.getOrderReference())
                .refundAmount(app.getRefundAmount())
                .originalAmount(app.getOriginalAmount())
                .applicantUserId(app.getApplicantUserId())
                .refundReason(app.getRefundReason())
                .status(app.getStatus())
                .statusDescription(RefundApplicationStatus.valueOf(app.getStatus()).getDescription())
                .reviewedBy(app.getReviewedBy())
                .reviewedAt(app.getReviewedAt())
                .reviewRemark(app.getReviewRemark())
                .refundNo(app.getRefundNo())
                .completedAt(app.getCompletedAt())
                .failReason(app.getFailReason())
                .applyTime(app.getCreateTime())
                .updateTime(app.getUpdateTime())
                .build();
    }
}
