package sys.smc.payment.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.dto.ApiResponse;
import sys.smc.payment.dto.RefundApplyRequest;
import sys.smc.payment.dto.RefundApplicationVO;
import sys.smc.payment.exception.UnauthorizedException;
import sys.smc.payment.security.UserContext;
import sys.smc.payment.security.UserContextHolder;
import sys.smc.payment.service.RefundApplicationService;

/**
 * 退款申请控制器（用户端）
 *
 * 所有接口均需登录（AuthFilter 验证 JWT），但不要求财务权限。
 * 路径 /api/refund/** 不在 FinanceAuthInterceptor 拦截范围内。
 */
@RestController
@RequestMapping("/api/refund")
@Slf4j
public class RefundApplicationController {

    @Autowired
    private RefundApplicationService refundApplicationService;

    /**
     * 提交退款申请
     *
     * POST /api/refund/apply
     * Header: Authorization: Bearer <JWT>
     * Body: { "transactionId": "TXN...", "refundAmount": 50.00, "refundReason": "商品有问题" }
     *
     * Response: { "code": 0, "data": { "applicationId": 123456 } }
     */
    @PostMapping("/apply")
    public ApiResponse<Long> apply(@Validated @RequestBody RefundApplyRequest request) {
        UserContext user = requireLogin();
        log.info("退款申请：userId={}，transactionId={}，amount={}",
                user.getUserId(), request.getTransactionId(), request.getRefundAmount());
        Long applicationId = refundApplicationService.applyRefund(request, user);
        return ApiResponse.success(applicationId);
    }

    /**
     * 查询退款申请状态（用户只能查自己的）
     *
     * GET /api/refund/status/{applicationId}
     */
    @GetMapping("/status/{applicationId}")
    public ApiResponse<RefundApplicationVO> status(@PathVariable Long applicationId) {
        UserContext user = requireLogin();
        RefundApplicationVO vo = refundApplicationService.getApplicationStatus(applicationId, user.getUserId());
        return ApiResponse.success(vo);
    }

    // ─── helper ───

    private UserContext requireLogin() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            throw UnauthorizedException.unauthenticated("请先登录");
        }
        return ctx;
    }
}
