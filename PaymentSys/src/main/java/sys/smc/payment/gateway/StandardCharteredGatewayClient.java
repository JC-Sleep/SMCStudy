package sys.smc.payment.gateway;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sys.smc.payment.dto.PaymentCallbackData;
import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.dto.RefundRequest;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.exception.GatewayException;
import sys.smc.payment.gateway.dto.GatewayPaymentResponse;
import sys.smc.payment.gateway.dto.GatewayRefundResponse;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;

import java.util.*;

/**
 * 渣打银行网关客户端
 * 实现PaymentGateway接口，支持统一的支付网关抽象
 */
@Component
@Slf4j
public class StandardCharteredGatewayClient extends AbstractPaymentGateway {

    @Value("${scb.api.endpoint:}")
    private String apiEndpoint;

    @Value("${scb.api.key:}")
    private String apiKey;

    @Value("${scb.api.secret:}")
    private String apiSecret;

    @Value("${payment.callback.url:}")
    private String callbackUrl;

    // ==================== PaymentGateway接口实现 ====================

    @Override
    public PaymentChannel getChannel() {
        return PaymentChannel.STANDARD_CHARTERED;
    }

    @Override
    public String getChannelName() {
        return "渣打银行";
    }

    @Override
    public boolean supportsPaymentMethod(String paymentMethod) {
        // 渣打银行支持的支付方式
        return "CARD".equals(paymentMethod) 
            || "VISA".equals(paymentMethod)
            || "MASTERCARD".equals(paymentMethod)
            || "UNIONPAY".equals(paymentMethod);
    }

    @Override
    public int getPriority() {
        return 10; // 银行渠道优先级高
    }

    // ==================== 抽象方法实现 ====================

    @Override
    protected String getApiEndpoint() {
        return apiEndpoint;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getApiSecret() {
        return apiSecret;
    }

    @Override
    protected String getCallbackUrl() {
        return callbackUrl + "/api/payment/callback/standard-chartered";
    }

    @Override
    protected Logger getLogger() {
        return log;
    }

    @Override
    protected Map<String, Object> buildPaymentRequest(PaymentInitRequest request, String transactionId) {
        Map<String, Object> gatewayRequest = new TreeMap<>();
        gatewayRequest.put("merchantId", apiKey);
        gatewayRequest.put("transactionId", transactionId);
        gatewayRequest.put("orderReference", request.getOrderReference());
        gatewayRequest.put("amount", request.getAmount());
        gatewayRequest.put("currency", request.getCurrency() != null ? request.getCurrency() : "HKD");
        gatewayRequest.put("callbackUrl", getCallbackUrl());
        gatewayRequest.put("returnUrl", request.getReturnUrl());
        gatewayRequest.put("timestamp", System.currentTimeMillis());
        return gatewayRequest;
    }

    @Override
    protected GatewayPaymentResponse parsePaymentResponse(String responseBody) {
        return JSON.parseObject(responseBody, GatewayPaymentResponse.class);
    }

    @Override
    protected GatewayTransactionStatus parseStatusResponse(String responseBody) {
        return JSON.parseObject(responseBody, GatewayTransactionStatus.class);
    }

    @Override
    protected String buildSignature(Map<String, Object> data) {
        return generateSignature(data);
    }

    // ==================== 核心业务方法 ====================

    /**
     * 创建支付
     */
    @Override
    public GatewayPaymentResponse createPayment(PaymentInitRequest request, String transactionId) {
        String url = apiEndpoint + "/create";
        Map<String, Object> gatewayRequest = buildPaymentRequest(request, transactionId);
        String signature = generateSignature(gatewayRequest);

        try {
            log.info("[渣打银行] 创建支付，URL：{}，交易ID：{}", url, transactionId);

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
            log.info("[渣打银行] 响应：{}", body);

            GatewayPaymentResponse gatewayResponse = parsePaymentResponse(body);

            if (gatewayResponse == null || !"SUCCESS".equals(gatewayResponse.getStatus())) {
                throw new GatewayException("创建支付失败：" + 
                    (gatewayResponse != null ? gatewayResponse.getMessage() : "未知错误"));
            }

            return gatewayResponse;

        } catch (Exception e) {
            log.error("[渣打银行] 创建支付失败", e);
            throw new GatewayException("创建支付失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询交易状态
     */
    @Override
    public GatewayTransactionStatus queryTransactionStatus(String gatewayTransactionId) {
        String url = apiEndpoint + "/query/" + gatewayTransactionId;

        try {
            log.info("[渣打银行] 查询交易状态，银行交易ID：{}", gatewayTransactionId);

            HttpResponse response = HttpRequest.get(url)
                .header("X-API-Key", apiKey)
                .timeout(30000)
                .execute();

            if (!response.isOk()) {
                throw new GatewayException("网关返回错误：" + response.getStatus());
            }

            String body = response.body();
            log.info("[渣打银行] 响应：{}", body);

            return parseStatusResponse(body);

        } catch (Exception e) {
            log.error("[渣打银行] 查询交易状态失败", e);
            throw new GatewayException("查询状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 关闭订单
     */
    @Override
    public boolean closeOrder(String gatewayTransactionId) {
        String url = apiEndpoint + "/close/" + gatewayTransactionId;

        try {
            log.info("[渣打银行] 关闭订单，银行交易ID：{}", gatewayTransactionId);

            HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", apiKey)
                .timeout(30000)
                .execute();

            return response.isOk();

        } catch (Exception e) {
            log.error("[渣打银行] 关闭订单失败", e);
            return false;
        }
    }

    /**
     * 退款
     */
    @Override
    public GatewayRefundResponse refund(RefundRequest request) {
        String url = apiEndpoint + "/refund";

        Map<String, Object> refundRequest = new TreeMap<>();
        refundRequest.put("merchantId", apiKey);
        refundRequest.put("gatewayTransactionId", request.getGatewayTransactionId());
        refundRequest.put("refundAmount", request.getRefundAmount());
        refundRequest.put("refundReason", request.getRefundReason());
        refundRequest.put("refundNo", request.getRefundNo());
        refundRequest.put("timestamp", System.currentTimeMillis());

        String signature = generateSignature(refundRequest);

        try {
            log.info("[渣打银行] 申请退款，交易ID：{}", request.getGatewayTransactionId());

            HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", apiKey)
                .header("X-Signature", signature)
                .header("Content-Type", "application/json")
                .body(JSON.toJSONString(refundRequest))
                .timeout(30000)
                .execute();

            String body = response.body();
            log.info("[渣打银行] 退款响应：{}", body);

            JSONObject json = JSON.parseObject(body);
            
            return GatewayRefundResponse.builder()
                .success("SUCCESS".equals(json.getString("status")))
                .refundNo(json.getString("refundNo"))
                .gatewayRefundId(json.getString("gatewayRefundId"))
                .refundStatus(json.getString("refundStatus"))
                .errorCode(json.getString("errorCode"))
                .errorMessage(json.getString("errorMessage"))
                .rawResponse(body)
                .build();

        } catch (Exception e) {
            log.error("[渣打银行] 退款失败", e);
            return GatewayRefundResponse.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    /**
     * 验证回调签名
     */
    @Override
    public boolean verifyCallback(String rawBody, String signature, Map<String, String> headers) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        
        // 使用原始body重新计算签名
        String expectedSignature = SecureUtil.hmacSha256(apiSecret).digestHex(rawBody);
        return signature.equals(expectedSignature);
    }

    /**
     * 解析回调数据
     */
    @Override
    public PaymentCallbackData parseCallbackData(String rawBody) {
        JSONObject json = JSON.parseObject(rawBody);
        
        PaymentCallbackData data = new PaymentCallbackData();
        data.setGatewayTransactionId(json.getString("gatewayTransactionId"));
        data.setOrderReference(json.getString("orderReference"));
        data.setAmount(json.getBigDecimal("amount"));
        data.setPaymentStatus(json.getString("status"));
        data.setPaymentMethod(json.getString("paymentMethod"));
        data.setPaymentTime(json.getDate("paymentTime"));
        data.setRawData(rawBody);
        
        return data;
    }

    // ==================== 私有方法 ====================

    /**
     * 生成签名（HMAC-SHA256）
     */
    private String generateSignature(Map<String, Object> data) {
        TreeMap<String, Object> sortedData = new TreeMap<>(data);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sortedData.entrySet()) {
            if (entry.getValue() != null) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }

        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }

        String signStr = sb.toString();
        log.debug("[渣打银行] 待签名字符串：{}", signStr);

        String signature = SecureUtil.hmacSha256(apiSecret).digestHex(signStr);
        log.debug("[渣打银行] 签名结果：{}", signature);

        return signature;
    }
}
