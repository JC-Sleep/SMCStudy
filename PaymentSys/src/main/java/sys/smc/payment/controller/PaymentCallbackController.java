package sys.smc.payment.controller;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.service.PaymentCallbackService;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 支付回调控制器
 * 支持多渠道回调：渣打银行、支付宝、建设银行等
 */
@RestController
@RequestMapping("/api/payment/callback")
@Slf4j
public class PaymentCallbackController {

    @Autowired
    private PaymentCallbackService callbackService;

    /**
     * 通用回调处理器
     * 根据渠道路径自动路由到对应处理逻辑
     */
    @PostMapping("/{channel}")
    public ResponseEntity<String> handleCallback(
            @PathVariable String channel,
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {

        long startTime = System.currentTimeMillis();
        String clientIp = getClientIp(request);

        log.info("收到 {} 回调，IP：{}，Header数量：{}", channel, clientIp, headers.size());

        try {
            // 获取渠道枚举
            PaymentChannel paymentChannel = PaymentChannel.fromCallbackPath(channel);
            
            // 异步处理回调（立即返回，防止重试风暴）
            callbackService.processCallback(rawBody, headers, clientIp, paymentChannel);

            // 返回渠道对应的成功响应
            return buildSuccessResponse(paymentChannel);

        } catch (IllegalArgumentException e) {
            log.error("未知的回调渠道：{}", channel);
            return ResponseEntity.badRequest().body("UNKNOWN_CHANNEL");

        } catch (Exception e) {
            log.error("处理 {} 回调失败", channel, e);
            // 仍然返回成功，防止重试风暴
            return ResponseEntity.ok("RECEIVED");

        } finally {
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("{} 回调处理耗时：{}ms", channel, processingTime);
        }
    }

    /**
     * 渣打银行回调（保持向后兼容）
     */
    @PostMapping("/standard-chartered")
    public ResponseEntity<String> handleStandardCharteredCallback(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {
        return handleCallback("standard-chartered", rawBody, headers, request);
    }

    /**
     * 支付宝异步通知
     */
    @PostMapping("/alipay")
    public ResponseEntity<String> handleAlipayCallback(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {
        return handleCallback("alipay", rawBody, headers, request);
    }

    /**
     * 建设银行回调
     */
    @PostMapping("/ccb")
    public ResponseEntity<String> handleCCBCallback(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {
        return handleCallback("ccb", rawBody, headers, request);
    }

    /**
     * 构建成功响应（不同渠道要求不同）
     */
    private ResponseEntity<String> buildSuccessResponse(PaymentChannel channel) {
        switch (channel) {
            case ALIPAY:
                // 支付宝要求返回 "success" 字符串
                return ResponseEntity.ok("success");
            case CCB:
                // 建行要求返回 "OK"
                return ResponseEntity.ok("OK");
            default:
                return ResponseEntity.ok("SUCCESS");
        }
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个IP取第一个
        if (StrUtil.isNotBlank(ip) && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
