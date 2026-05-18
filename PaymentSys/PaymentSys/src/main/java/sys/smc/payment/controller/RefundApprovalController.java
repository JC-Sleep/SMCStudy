package sys.smc.payment.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.dto.ApiResponse;
import sys.smc.payment.dto.RefundApproveRequest;
import sys.smc.payment.entity.RefundApplication;
import sys.smc.payment.entity.RefundAuditLog;
import sys.smc.payment.security.UserContext;
import sys.smc.payment.security.UserContextHolder;
import sys.smc.payment.service.RefundApprovalService;

import java.util.List;

/**
 * 财务退款审批控制器（财务后台专用）
 *
 * 安全保障（三层）：
 *   1. AuthFilter    - 解析 JWT，填充 UserContextHolder
 *   2. FinanceAuthInterceptor - 拦截 /api/finance/**，验证 parentGroupId=345/59
 *   3. @RequireFinance AOP   - Service 方法级别二次验证
 *
 * ⚠️ 此路径下任何请求，非财务人员均返回 403
 */
@RestController
@RequestMapping("/api/finance/refund")
@Slf4j
public class RefundApprovalController {

    @Autowired
    private RefundApprovalService refundApprovalService;

    /**
     * 查询退款申请列表（分页）
     *
     * GET /api/finance/refund/list?status=PENDING_REVIEW&page=1&size=20
     *
     * status 可选值：PENDING_REVIEW | APPROVED | REJECTED | EXECUTING | COMPLETED | FAILED | (空=全部)
     */
    @GetMapping("/list")
    public ApiResponse<Page<RefundApplication>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        UserContext finance = UserContextHolder.get();
        log.info("[财务] 查询退款列表：userId={}，status={}，page={}", finance.getUserId(), status, page);
        Page<RefundApplication> result = refundApprovalService.listApplications(status, page, size);
        return ApiResponse.success(result);
    }

    /**
     * 批准退款申请（触发异步退款到银行）
     *
     * POST /api/finance/refund/approve
     * Body: { "applicationId": 123, "reviewRemark": "金额核实无误" }
     *
     * 响应：立即返回（退款异步执行），通过 /status 轮询结果
     */
    @PostMapping("/approve")
    public ApiResponse<Void> approve(@Validated @RequestBody RefundApproveRequest request) {
        UserContext finance = UserContextHolder.get();
        log.info("[财务] 批准退款：userId={}，isManager={}，applicationId={}",
                finance.getUserId(), finance.isManager(), request.getApplicationId());
        refundApprovalService.approveApplication(request, finance);
        return ApiResponse.success(null, "已批准，退款正在异步处理中，请稍后查询状态");
    }

    /**
     * 拒绝退款申请
     *
     * POST /api/finance/refund/reject
     * Body: { "applicationId": 123, "reviewRemark": "订单已超过退款期限" }
     */
    @PostMapping("/reject")
    public ApiResponse<Void> reject(@Validated @RequestBody RefundApproveRequest request) {
        UserContext finance = UserContextHolder.get();
        log.info("[财务] 拒绝退款：userId={}，applicationId={}", finance.getUserId(), request.getApplicationId());
        refundApprovalService.rejectApplication(request, finance);
        return ApiResponse.success(null, "已拒绝");
    }

    /**
     * 查看某申请的完整审计日志（不可篡改流水）
     *
     * GET /api/finance/refund/audit-log/{applicationId}
     */
    @GetMapping("/audit-log/{applicationId}")
    public ApiResponse<List<RefundAuditLog>> auditLog(@PathVariable Long applicationId) {
        UserContext finance = UserContextHolder.get();
        log.info("[财务] 查询审计日志：userId={}，applicationId={}", finance.getUserId(), applicationId);
        List<RefundAuditLog> logs = refundApprovalService.getAuditLog(applicationId);
        return ApiResponse.success(logs);
    }
}

