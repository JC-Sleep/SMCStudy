package sys.smc.payment.gateway;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.exception.GatewayException;
import sys.smc.payment.gateway.dto.GatewayPaymentResponse;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 渣打银行网关客户端
 */
@Component
@Slf4j
public class StandardCharteredGatewayClient {

    @Value("${scb.api.endpoint}")
    private String apiEndpoint;

    @Value("${scb.api.key}")
    private String apiKey;

    @Value("${scb.api.secret}")
    private String apiSecret;

    @Value("${payment.callback.url}")
    private String callbackUrl;

    /**
     * 创建支付
     */
    public GatewayPaymentResponse createPayment(PaymentInitRequest request, String transactionId) {
        String url = apiEndpoint + "/create";

        // 构建请求
        Map<String, Object> gatewayRequest = new TreeMap<>();
        gatewayRequest.put("merchantId", apiKey);
        gatewayRequest.put("transactionId", transactionId);
        gatewayRequest.put("orderReference", request.getOrderReference());
        gatewayRequest.put("amount", request.getAmount());
        gatewayRequest.put("currency", request.getCurrency());
        gatewayRequest.put("callbackUrl", callbackUrl);
        gatewayRequest.put("returnUrl", request.getReturnUrl());
        gatewayRequest.put("timestamp", System.currentTimeMillis());

        // 签名请求
        String signature = generateSignature(gatewayRequest);

        try {
            log.info("调用渣打银行创建支付API，URL：{}，交易ID：{}", url, transactionId);

            HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", apiKey)
                .header("X-Signature", signature)
                .header("Content-Type", "application/json")
                .body(JSON.toJSONString(gatewayRequest))
                .timeout(30000)
                .execute();

            if (!response.isOk()) {
                throw new GatewayException("网关返回错误：" + response.getStatus());
            }

            String body = response.body();
            log.info("渣打银行响应：{}", body);

            GatewayPaymentResponse gatewayResponse = JSON.parseObject(body, GatewayPaymentResponse.class);

            if (gatewayResponse == null || !"SUCCESS".equals(gatewayResponse.getStatus())) {
                throw new GatewayException("创建支付失败：" + (gatewayResponse != null ? gatewayResponse.getMessage() : "未知错误"));
            }

            return gatewayResponse;

        } catch (Exception e) {
            log.error("调用渣打银行API失败", e);
            throw new GatewayException("创建支付失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询交易状态
     */
    public GatewayTransactionStatus queryTransactionStatus(String gatewayTransactionId) {
        String url = apiEndpoint + "/query/" + gatewayTransactionId;

        try {
            log.info("查询渣打银行交易状态，银行交易ID：{}", gatewayTransactionId);

            HttpResponse response = HttpRequest.get(url)
                .header("X-API-Key", apiKey)
                .timeout(30000)
                .execute();

            if (!response.isOk()) {
                throw new GatewayException("网关返回错误：" + response.getStatus());
            }

            String body = response.body();
            log.info("渣打银行响应：{}", body);

            return JSON.parseObject(body, GatewayTransactionStatus.class);

        } catch (Exception e) {
            log.error("查询交易状态失败", e);
            throw new GatewayException("查询状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 生成签名（HMAC-SHA256）
     */
    private String generateSignature(Map<String, Object> data) {
        // 按字母顺序排序键
        TreeMap<String, Object> sortedData = new TreeMap<>(data);

        // 连接所有值
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sortedData.entrySet()) {
            if (entry.getValue() != null) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }

        // 移除最后的&
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }

        String signStr = sb.toString();
        log.debug("待签名字符串：{}", signStr);

        // HMAC-SHA256签名
        String signature = SecureUtil.hmacSha256(apiSecret).digestHex(signStr);
        log.debug("签名结果：{}", signature);

        return signature;
    }
}

