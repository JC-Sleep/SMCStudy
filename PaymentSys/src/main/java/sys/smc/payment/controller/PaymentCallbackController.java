package sys.smc.payment.controller;

import cn.hutool.core.util.StrUtil;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.service.PaymentCallbackServiceEnhanced;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 支付回调控制器
 * 支持多渠道回调：渣打银行、支付宝、建设银行等
 *
 * 修复：注入增强版回调服务（含Redis去重），废弃基础版
 * ⑩ 修复：增加回调限流，防止银行重试风暴打穿 DB
 */
@RestController
@RequestMapping("/api/payment/callback")
@Slf4j
public class PaymentCallbackController {

    /** ⭐ 使用增强版：含 Redis去重 + 终态检查 + 乐观锁三层防护 */
    @Autowired
    private PaymentCallbackServiceEnhanced callbackService;

    // ⑩ 回调限流（银行可能重发，需要适当限速）
    @Autowired
    @Qualifier("callbackRateLimiter")
    private RateLimiter callbackRateLimiter;

    /**
     * 通用回调处理器
     * 根据渠道路径自动路由到对应处理逻辑
     * ⑩ 限流：超过阈值时仍返回 200（告诉银行"收到了"，避免无限重发）
     */
    @PostMapping("/{channel}")
    public ResponseEntity<String> handleCallback(
            @PathVariable String channel,
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {

        long startTime = System.currentTimeMillis();
        String clientIp = getClientIp(request);

        // ⑩ 限流检查：超限时返回200但不处理
        // 注意：必须返回200，否则银行会认为失败并重试，加剧问题
        if (!callbackRateLimiter.tryAcquire()) {
            log.warn("回调限流触发，渠道：{}，IP：{}", channel, clientIp);
            return ResponseEntity.ok("RECEIVED"); // 返回200，防止银行重发
        }

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
