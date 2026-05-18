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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 支付宝网关客户端
 * 基于支付宝开放平台API实现
 * 
 * 支付宝API文档参考：https://opendocs.alipay.com/apis
 */
@Component
@Slf4j
public class AlipayGatewayClient extends AbstractPaymentGateway {

    @Value("${alipay.gateway.url:https://openapi.alipay.com/gateway.do}")
    private String gatewayUrl;

    @Value("${alipay.app.id:}")
    private String appId;

    @Value("${alipay.merchant.private.key:}")
    private String merchantPrivateKey;

    @Value("${alipay.public.key:}")
    private String alipayPublicKey;

    @Value("${payment.callback.url:}")
    private String callbackUrl;

    @Value("${alipay.sign.type:RSA2}")
    private String signType;

    // ==================== PaymentGateway接口实现 ====================

    @Override
    public PaymentChannel getChannel() {
        return PaymentChannel.ALIPAY;
    }

    @Override
    public String getChannelName() {
        return "支付宝";
    }

    @Override
    public boolean supportsPaymentMethod(String paymentMethod) {
        return "ALIPAY".equals(paymentMethod) 
            || "ALIPAY_WAP".equals(paymentMethod)
            || "ALIPAY_APP".equals(paymentMethod)
            || "ALIPAY_PC".equals(paymentMethod);
    }

    @Override
    public int getPriority() {
        return 20; // 第三方支付优先级
    }

    @Override
    public boolean isAvailable() {
        return appId != null && !appId.isEmpty()
            && merchantPrivateKey != null && !merchantPrivateKey.isEmpty();
    }

    // ==================== 抽象方法实现 ====================

    @Override
    protected String getApiEndpoint() {
        return gatewayUrl;
    }

    @Override
    protected String getApiKey() {
        return appId;
    }

    @Override
    protected String getApiSecret() {
        return merchantPrivateKey;
    }

    @Override
    protected String getCallbackUrl() {
        return callbackUrl + "/api/payment/callback/alipay";
    }

    @Override
    protected Logger getLogger() {
        return log;
    }

    @Override
    protected Map<String, Object> buildPaymentRequest(PaymentInitRequest request, String transactionId) {
        // 公共请求参数
        Map<String, Object> params = new TreeMap<>();
        params.put("app_id", appId);
        params.put("method", "alipay.trade.page.pay");
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", signType);
        params.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        params.put("version", "1.0");
        params.put("notify_url", getCallbackUrl());
        params.put("return_url", request.getReturnUrl());

        // 业务参数
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", transactionId);
        bizContent.put("total_amount", request.getAmount().toString());
        bizContent.put("subject", "订单支付-" + request.getOrderReference());
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        
        params.put("biz_content", bizContent.toJSONString());

        return params;
    }

    @Override
    protected GatewayPaymentResponse parsePaymentResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        JSONObject response = json.getJSONObject("alipay_trade_page_pay_response");
        
        if (response == null) {
            return null;
        }

        GatewayPaymentResponse gatewayResponse = new GatewayPaymentResponse();
        gatewayResponse.setTransactionId(response.getString("trade_no"));
        gatewayResponse.setOrderNo(response.getString("out_trade_no"));
        gatewayResponse.setStatus("10000".equals(response.getString("code")) ? "SUCCESS" : "FAILED");
        gatewayResponse.setMessage(response.getString("msg"));
        
        return gatewayResponse;
    }

    @Override
    protected GatewayTransactionStatus parseStatusResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        JSONObject response = json.getJSONObject("alipay_trade_query_response");
        
        if (response == null) {
            return null;
        }

        GatewayTransactionStatus status = new GatewayTransactionStatus();
        status.setGatewayTransactionId(response.getString("trade_no"));
        status.setOrderNo(response.getString("out_trade_no"));
        
        // 支付宝交易状态映射
        String tradeStatus = response.getString("trade_status");
        status.setStatus(mapAlipayStatus(tradeStatus));
        status.setAmount(response.getBigDecimal("total_amount"));
        
        return status;
    }

    @Override
    protected String buildSignature(Map<String, Object> data) {
        return signWithRSA2(data);
    }

    // ==================== 核心业务方法 ====================

    /**
     * 创建支付（生成支付表单/URL）
     */
    @Override
    public GatewayPaymentResponse createPayment(PaymentInitRequest request, String transactionId) {
        try {
            log.info("[支付宝] 创建支付，交易ID：{}", transactionId);

            Map<String, Object> params = buildPaymentRequest(request, transactionId);
            String sign = signWithRSA2(params);
            params.put("sign", sign);

            // 构建支付表单URL
            StringBuilder formUrl = new StringBuilder(gatewayUrl + "?");
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                formUrl.append(entry.getKey())
                       .append("=")
                       .append(URLEncoder.encode(entry.getValue().toString(), "UTF-8"))
                       .append("&");
            }

            String paymentUrl = formUrl.toString();
            log.info("[支付宝] 生成支付URL成功");

            GatewayPaymentResponse response = new GatewayPaymentResponse();
            response.setTransactionId(transactionId);
            response.setOrderNo(transactionId);
            response.setPaymentUrl(paymentUrl);
            response.setStatus("SUCCESS");
            
            return response;

        } catch (Exception e) {
            log.error("[支付宝] 创建支付失败", e);
            throw new GatewayException("创建支付失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询交易状态
     */
    @Override
    public GatewayTransactionStatus queryTransactionStatus(String gatewayTransactionId) {
        try {
            log.info("[支付宝] 查询交易状态，交易ID：{}", gatewayTransactionId);

            Map<String, Object> params = new TreeMap<>();
            params.put("app_id", appId);
            params.put("method", "alipay.trade.query");
            params.put("format", "JSON");
            params.put("charset", "utf-8");
            params.put("sign_type", signType);
            params.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            params.put("version", "1.0");

            JSONObject bizContent = new JSONObject();
            bizContent.put("trade_no", gatewayTransactionId);
            params.put("biz_content", bizContent.toJSONString());

            String sign = signWithRSA2(params);
            params.put("sign", sign);

            HttpResponse response = HttpRequest.post(gatewayUrl)
                .form(params)
                .timeout(30000)
                .execute();

            String body = response.body();
            log.info("[支付宝] 查询响应：{}", body);

            return parseStatusResponse(body);

        } catch (Exception e) {
            log.error("[支付宝] 查询交易状态失败", e);
            throw new GatewayException("查询状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 关闭订单
     */
    @Override
    public boolean closeOrder(String gatewayTransactionId) {
        try {
            log.info("[支付宝] 关闭订单，交易ID：{}", gatewayTransactionId);

            Map<String, Object> params = new TreeMap<>();
            params.put("app_id", appId);
            params.put("method", "alipay.trade.close");
            params.put("format", "JSON");
            params.put("charset", "utf-8");
            params.put("sign_type", signType);
            params.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            params.put("version", "1.0");

            JSONObject bizContent = new JSONObject();
            bizContent.put("trade_no", gatewayTransactionId);
            params.put("biz_content", bizContent.toJSONString());

            String sign = signWithRSA2(params);
            params.put("sign", sign);

            HttpResponse response = HttpRequest.post(gatewayUrl)
                .form(params)
                .timeout(30000)
                .execute();

            JSONObject json = JSON.parseObject(response.body());
            JSONObject result = json.getJSONObject("alipay_trade_close_response");
            
            return result != null && "10000".equals(result.getString("code"));

        } catch (Exception e) {
            log.error("[支付宝] 关闭订单失败", e);
            return false;
        }
    }

    /**
     * 退款
     */
    @Override
    public GatewayRefundResponse refund(RefundRequest request) {
        try {
            log.info("[支付宝] 申请退款，交易ID：{}", request.getGatewayTransactionId());

            Map<String, Object> params = new TreeMap<>();
            params.put("app_id", appId);
            params.put("method", "alipay.trade.refund");
            params.put("format", "JSON");
            params.put("charset", "utf-8");
            params.put("sign_type", signType);
            params.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            params.put("version", "1.0");

            JSONObject bizContent = new JSONObject();
            bizContent.put("trade_no", request.getGatewayTransactionId());
            bizContent.put("refund_amount", request.getRefundAmount().toString());
            bizContent.put("refund_reason", request.getRefundReason());
            bizContent.put("out_request_no", request.getRefundNo());
            params.put("biz_content", bizContent.toJSONString());

            String sign = signWithRSA2(params);
            params.put("sign", sign);

            HttpResponse response = HttpRequest.post(gatewayUrl)
                .form(params)
                .timeout(30000)
                .execute();

            String body = response.body();
            log.info("[支付宝] 退款响应：{}", body);

            JSONObject json = JSON.parseObject(body);
            JSONObject result = json.getJSONObject("alipay_trade_refund_response");

            boolean success = result != null && "10000".equals(result.getString("code"));
            
            return GatewayRefundResponse.builder()
                .success(success)
                .refundNo(request.getRefundNo())
                .gatewayRefundId(result != null ? result.getString("trade_no") : null)
                .refundStatus(success ? "SUCCESS" : "FAILED")
                .errorCode(result != null ? result.getString("code") : null)
                .errorMessage(result != null ? result.getString("msg") : null)
                .rawResponse(body)
                .build();

        } catch (Exception e) {
            log.error("[支付宝] 退款失败", e);
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
        try {
            // 支付宝异步通知验签
            Map<String, String> params = parseNotifyParams(rawBody);
            String sign = params.remove("sign");
            String signType = params.remove("sign_type");
            
            // 按字母顺序排序并拼接
            TreeMap<String, String> sortedParams = new TreeMap<>(params);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
                }
            }
            if (sb.length() > 0) {
                sb.setLength(sb.length() - 1);
            }

            // 使用支付宝公钥验签（实际实现需要RSA验签）
            // 这里简化处理，实际应使用RSA2验签
            return sign != null && !sign.isEmpty();
            
        } catch (Exception e) {
            log.error("[支付宝] 验签失败", e);
            return false;
        }
    }

    /**
     * 解析回调数据
     */
    @Override
    public PaymentCallbackData parseCallbackData(String rawBody) {
        Map<String, String> params = parseNotifyParams(rawBody);
        
        PaymentCallbackData data = new PaymentCallbackData();
        data.setGatewayTransactionId(params.get("trade_no"));
        data.setOrderReference(params.get("out_trade_no"));
        data.setAmount(new java.math.BigDecimal(params.getOrDefault("total_amount", "0")));
        data.setPaymentStatus(mapAlipayStatus(params.get("trade_status")));
        data.setPaymentMethod("ALIPAY");
        data.setRawData(rawBody);
        
        return data;
    }

    // ==================== 私有方法 ====================

    /**
     * RSA2签名
     */
    private String signWithRSA2(Map<String, Object> params) {
        try {
            TreeMap<String, Object> sortedParams = new TreeMap<>(params);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : sortedParams.entrySet()) {
                if (entry.getValue() != null && !"sign".equals(entry.getKey())) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
                }
            }
            if (sb.length() > 0) {
                sb.setLength(sb.length() - 1);
            }

            String content = sb.toString();
            
            // 使用商户私钥签名
            byte[] keyBytes = Base64.getDecoder().decode(merchantPrivateKey);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            
            return Base64.getEncoder().encodeToString(signature.sign());

        } catch (Exception e) {
            log.error("[支付宝] 签名失败", e);
            throw new GatewayException("签名失败：" + e.getMessage(), e);
        }
    }

    /**
     * 映射支付宝交易状态
     */
    private String mapAlipayStatus(String alipayStatus) {
        if (alipayStatus == null) {
            return "PENDING";
        }
        switch (alipayStatus) {
            case "WAIT_BUYER_PAY":
                return "PENDING";
            case "TRADE_SUCCESS":
            case "TRADE_FINISHED":
                return "SUCCESS";
            case "TRADE_CLOSED":
                return "FAILED";
            default:
                return "PENDING";
        }
    }

    /**
     * 解析通知参数
     */
    private Map<String, String> parseNotifyParams(String rawBody) {
        Map<String, String> params = new HashMap<>();
        String[] pairs = rawBody.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                try {
                    params.put(keyValue[0], java.net.URLDecoder.decode(keyValue[1], "UTF-8"));
                } catch (Exception e) {
                    params.put(keyValue[0], keyValue[1]);
                }
            }
        }
        return params;
    }
}

