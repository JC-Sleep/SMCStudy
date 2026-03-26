package sys.smc.payment.controller;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.service.PaymentCallbackService;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 支付回调控制器
 */
@RestController
@RequestMapping("/api/payment/callback")
@Slf4j
public class PaymentCallbackController {

    @Autowired
    private PaymentCallbackService callbackService;

    /**
     * 处理渣打银行回调
     */
    @PostMapping("/standard-chartered")
    public ResponseEntity<String> handleStandardCharteredCallback(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {

        long startTime = System.currentTimeMillis();
        String clientIp = getClientIp(request);

        log.info("收到渣打银行回调，IP：{}，Header数量：{}", clientIp, headers.size());

        try {
            // 异步处理回调（立即返回，防止银行重试风暴）
            callbackService.processCallback(rawBody, headers, clientIp);

            // 立即返回成功
            return ResponseEntity.ok("SUCCESS");

        } catch (Exception e) {
            log.error("处理回调失败", e);
            // 仍然返回成功，防止银行重试风暴
            // 对账任务会修复问题
            return ResponseEntity.ok("RECEIVED");

        } finally {
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("回调处理耗时：{}ms", processingTime);
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

