package sys.smc.payment.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.dto.ApiResponse;
import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.dto.PaymentInitResponse;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.service.PaymentService;

/**
 * 支付控制器
 */
@RestController
@RequestMapping("/api/payment")
@Slf4j
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    /**
     * 发起支付
     */
    @PostMapping("/initiate")
    public ApiResponse<PaymentInitResponse> initiatePayment(
            @Validated @RequestBody PaymentInitRequest request) {
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
     */
    @GetMapping("/status/{transactionId}")
    public ApiResponse<PaymentTransaction> queryStatus(@PathVariable String transactionId) {
        try {
            log.info("查询支付状态，交易ID：{}", transactionId);
            PaymentTransaction transaction = paymentService.queryPaymentStatus(transactionId);
            if (transaction == null) {
                return ApiResponse.error("交易不存在");
            }
            return ApiResponse.success(transaction);
        } catch (Exception e) {
            log.error("查询支付状态失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 根据订单号查询
     */
    @GetMapping("/order/{orderReference}")
    public ApiResponse<PaymentTransaction> queryByOrder(@PathVariable String orderReference) {
        try {
            log.info("根据订单号查询，订单号：{}", orderReference);
            PaymentTransaction transaction = paymentService.queryByOrderReference(orderReference);
            if (transaction == null) {
                return ApiResponse.error("订单不存在");
            }
            return ApiResponse.success(transaction);
        } catch (Exception e) {
            log.error("查询订单失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}

