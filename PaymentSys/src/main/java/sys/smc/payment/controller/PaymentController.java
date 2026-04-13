package sys.smc.payment.controller;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.dto.ApiResponse;
import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.dto.PaymentInitResponse;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.service.PaymentService;

/**
 * 支付控制器
 * ⑩ 修复：增加 Guava RateLimiter 限流，防止突发流量打穿 DB 连接池
 */
@RestController
@RequestMapping("/api/payment")
@Slf4j
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    @Qualifier("paymentRateLimiter")
    private RateLimiter paymentRateLimiter;

    @Autowired
    @Qualifier("queryRateLimiter")
    private RateLimiter queryRateLimiter;

    /**
     * 发起支付
     * ⑩ 限流：每秒最多处理 paymentQps 笔支付（默认100）
     */
    @PostMapping("/initiate")
    public ApiResponse<PaymentInitResponse> initiatePayment(
            @Validated @RequestBody PaymentInitRequest request) {
        // tryAcquire()：非阻塞，拿不到令牌立刻返回 false（不等待）
        if (!paymentRateLimiter.tryAcquire()) {
            log.warn("支付限流触发，订单号：{}", request.getOrderReference());
            return ApiResponse.error("系统繁忙，请稍后重试（当前请求过多）");
        }
        try {
            log.info("收到支付请求，订单号：{}", request.getOrderReference());
            PaymentInitResponse response = paymentService.initiatePayment(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("支付发起失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 查询支付状态
     * ⑩ 限流：查询接口流量更大，单独限流
     */
    @GetMapping("/status/{transactionId}")
    public ApiResponse<PaymentTransaction> queryStatus(@PathVariable String transactionId) {
        if (!queryRateLimiter.tryAcquire()) {
            log.warn("查询限流触发，交易ID：{}", transactionId);
            return ApiResponse.error("系统繁忙，请稍后重试");
        }
        try {
            PaymentTransaction transaction = paymentService.queryPaymentStatus(transactionId);
            if (transaction == null) return ApiResponse.error("交易不存在");
            return ApiResponse.success(transaction);
        } catch (Exception e) {
            log.error("查询支付状态失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/order/{orderReference}")
    public ApiResponse<PaymentTransaction> queryByOrder(@PathVariable String orderReference) {
        if (!queryRateLimiter.tryAcquire()) {
            return ApiResponse.error("系统繁忙，请稍后重试");
        }
        try {
            PaymentTransaction transaction = paymentService.queryByOrderReference(orderReference);
            if (transaction == null) return ApiResponse.error("订单不存在");
            return ApiResponse.success(transaction);
        } catch (Exception e) {
            log.error("查询订单失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
