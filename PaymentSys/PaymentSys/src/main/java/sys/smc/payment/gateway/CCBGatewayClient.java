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

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 建设银行网关客户端
 * 基于建设银行龙支付/聚合支付API实现
 * 
 * 建设银行支付接入文档参考：
 * - 龙支付商户接入指南
 * - 建行聚合支付API文档
 */
@Component
@Slf4j
public class CCBGatewayClient extends AbstractPaymentGateway {

    @Value("${ccb.api.endpoint:https://ibsbjstar.ccb.com.cn/CCBIS/B2CMainPlat_00_LINKAGE}")
    private String apiEndpoint;

    @Value("${ccb.merchant.id:}")
    private String merchantId;

    @Value("${ccb.pos.id:}")
    private String posId;

    @Value("${ccb.branch.id:}")
    private String branchId;

    @Value("${ccb.pub.key:}")
    private String ccbPubKey;

    @Value("${ccb.merchant.key:}")
    private String merchantKey;

    @Value("${payment.callback.url:}")
    private String callbackUrl;

    // ==================== PaymentGateway接口实现 ====================

    @Override
    public PaymentChannel getChannel() {
        return PaymentChannel.CCB;
    }

    @Override
    public String getChannelName() {
        return "建设银行";
    }

    @Override
    public boolean supportsPaymentMethod(String paymentMethod) {
        return "CCB".equals(paymentMethod)
            || "CARD".equals(paymentMethod)
            || "DEBIT_CARD".equals(paymentMethod)
            || "CREDIT_CARD".equals(paymentMethod)
            || "CCB_QRCODE".equals(paymentMethod);
    }

    @Override
    public int getPriority() {
        return 15; // 银行渠道优先级
    }

    @Override
    public boolean isAvailable() {
        return merchantId != null && !merchantId.isEmpty()
            && posId != null && !posId.isEmpty()
            && merchantKey != null && !merchantKey.isEmpty();
    }

    // ==================== 抽象方法实现 ====================

    @Override
    protected String getApiEndpoint() {
        return apiEndpoint;
    }

    @Override
    protected String getApiKey() {
        return merchantId;
    }

    @Override
    protected String getApiSecret() {
        return merchantKey;
    }

    @Override
    protected String getCallbackUrl() {
        return callbackUrl + "/api/payment/callback/ccb";
    }

    @Override
    protected Logger getLogger() {
        return log;
    }

    @Override
    protected Map<String, Object> buildPaymentRequest(PaymentInitRequest request, String transactionId) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String timestamp = dateFormat.format(new Date());
        
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("MERCHANTID", merchantId);           // 商户代码
        params.put("POSID", posId);                     // 柜台代码
        params.put("BRANCHID", branchId);               // 分行代码
        params.put("ORDERID", transactionId);           // 订单号
        params.put("PAYMENT", request.getAmount().toString()); // 付款金额
        params.put("CURCODE", "01");                    // 币种（01=人民币）
        params.put("TXCODE", "530550");                 // 交易码（网关支付）
        params.put("REMARK1", request.getOrderReference()); // 备注1
        params.put("REMARK2", "");                      // 备注2
        params.put("TYPE", "1");                        // 接口类型（1=防钓鱼接口）
        params.put("GATEWAY", "");                      // 网关类型
        params.put("CLIENTIP", "");                     // 客户端IP
        params.put("REGINFO", "");                      // 客户注册信息
        params.put("PROINFO", "");                      // 商品信息
        params.put("REFERER", "");                      // 来源
        params.put("TIMEOUT", timestamp);               // 订单超时时间
        
        return params;
    }

    @Override
    protected GatewayPaymentResponse parsePaymentResponse(String responseBody) {
        // 建行返回的是key=value格式
        Map<String, String> resultMap = parseResponse(responseBody);
        
        GatewayPaymentResponse response = new GatewayPaymentResponse();
        response.setTransactionId(resultMap.get("ORDERID"));
        response.setOrderNo(resultMap.get("ORDERID"));
        response.setStatus("Y".equals(resultMap.get("SUCCESS")) ? "SUCCESS" : "FAILED");
        response.setMessage(resultMap.get("ERRMSG"));
        
        return response;
    }

    @Override
    protected GatewayTransactionStatus parseStatusResponse(String responseBody) {
        Map<String, String> resultMap = parseResponse(responseBody);
        
        GatewayTransactionStatus status = new GatewayTransactionStatus();
        status.setGatewayTransactionId(resultMap.get("ORDERID"));
        status.setOrderNo(resultMap.get("ORDERID"));
        status.setStatus(mapCCBStatus(resultMap.get("TRANSTAT")));
        
        String amountStr = resultMap.get("PAYMENT");
        if (amountStr != null) {
            status.setAmount(new java.math.BigDecimal(amountStr));
        }
        
        return status;
    }

    @Override
    protected String buildSignature(Map<String, Object> data) {
        return generateMD5Signature(data);
    }

    // ==================== 核心业务方法 ====================

    /**
     * 创建支付
     */
    @Override
    public GatewayPaymentResponse createPayment(PaymentInitRequest request, String transactionId) {
        try {
            log.info("[建设银行] 创建支付，交易ID：{}", transactionId);

            Map<String, Object> params = buildPaymentRequest(request, transactionId);
            
            // 生成MAC签名
            String mac = generateMD5Signature(params);
            params.put("MAC", mac);

            // 构建支付URL
            StringBuilder paymentUrl = new StringBuilder(apiEndpoint + "?");
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                paymentUrl.append(entry.getKey())
                         .append("=")
                         .append(entry.getValue())
                         .append("&");
            }

            log.info("[建设银行] 生成支付URL成功");

            GatewayPaymentResponse response = new GatewayPaymentResponse();
            response.setTransactionId(transactionId);
            response.setOrderNo(transactionId);
            response.setPaymentUrl(paymentUrl.toString());
            response.setStatus("SUCCESS");
            
            return response;

        } catch (Exception e) {
            log.error("[建设银行] 创建支付失败", e);
            throw new GatewayException("创建支付失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询交易状态
     */
    @Override
    public GatewayTransactionStatus queryTransactionStatus(String gatewayTransactionId) {
        try {
            log.info("[建设银行] 查询交易状态，交易ID：{}", gatewayTransactionId);

            // 建行查询接口
            String queryUrl = "https://ibsbjstar.ccb.com.cn/CCBIS/ccbMain";
            
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("MERCHANTID", merchantId);
            params.put("POSID", posId);
            params.put("BRANCHID", branchId);
            params.put("ORDERID", gatewayTransactionId);
            params.put("TXCODE", "410408");  // 查询交易码
            params.put("QUPWD", "");         // 查询密码
            params.put("TYPE", "0");
            
            String mac = generateMD5Signature(params);
            params.put("MAC", mac);

            HttpResponse response = HttpRequest.post(queryUrl)
                .form(params)
                .timeout(30000)
                .execute();

            String body = response.body();
            log.info("[建设银行] 查询响应：{}", body);

            return parseStatusResponse(body);

        } catch (Exception e) {
            log.error("[建设银行] 查询交易状态失败", e);
            throw new GatewayException("查询状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 关闭订单
     */
    @Override
    public boolean closeOrder(String gatewayTransactionId) {
        try {
            log.info("[建设银行] 关闭订单，交易ID：{}", gatewayTransactionId);
            // 建行不支持直接关闭订单，只能等待超时
            // 返回true表示不阻塞流程
            return true;

        } catch (Exception e) {
            log.error("[建设银行] 关闭订单失败", e);
            return false;
        }
    }

    /**
     * 退款
     */
    @Override
    public GatewayRefundResponse refund(RefundRequest request) {
        try {
            log.info("[建设银行] 申请退款，交易ID：{}", request.getGatewayTransactionId());

            // 建行退款接口
            String refundUrl = "https://ibsbjstar.ccb.com.cn/CCBIS/ccbMain";
            
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("MERCHANTID", merchantId);
            params.put("POSID", posId);
            params.put("BRANCHID", branchId);
            params.put("ORDERID", request.getGatewayTransactionId());
            params.put("TXCODE", "410409");  // 退款交易码
            params.put("MONEY", request.getRefundAmount().toString());
            params.put("TYPE", "1");
            
            String mac = generateMD5Signature(params);
            params.put("MAC", mac);

            HttpResponse response = HttpRequest.post(refundUrl)
                .form(params)
                .timeout(30000)
                .execute();

            String body = response.body();
            log.info("[建设银行] 退款响应：{}", body);

            Map<String, String> resultMap = parseResponse(body);
            boolean success = "Y".equals(resultMap.get("SUCCESS"));

            return GatewayRefundResponse.builder()
                .success(success)
                .refundNo(request.getRefundNo())
                .gatewayRefundId(resultMap.get("ORDERID"))
                .refundStatus(success ? "SUCCESS" : "FAILED")
                .errorMessage(resultMap.get("ERRMSG"))
                .rawResponse(body)
                .build();

        } catch (Exception e) {
            log.error("[建设银行] 退款失败", e);
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
            Map<String, String> params = parseResponse(rawBody);
            String mac = params.remove("MAC");
            
            // 重新计算签名
            Map<String, Object> signParams = new LinkedHashMap<>(params);
            String expectedMac = generateMD5Signature(signParams);
            
            return mac != null && mac.equalsIgnoreCase(expectedMac);

        } catch (Exception e) {
            log.error("[建设银行] 验签失败", e);
            return false;
        }
    }

    /**
     * 解析回调数据
     */
    @Override
    public PaymentCallbackData parseCallbackData(String rawBody) {
        Map<String, String> params = parseResponse(rawBody);
        
        PaymentCallbackData data = new PaymentCallbackData();
        data.setGatewayTransactionId(params.get("ORDERID"));
        data.setOrderReference(params.get("REMARK1"));
        
        String payment = params.get("PAYMENT");
        if (payment != null) {
            data.setAmount(new java.math.BigDecimal(payment));
        }
        
        data.setPaymentStatus(mapCCBStatus(params.get("SUCCESS")));
        data.setPaymentMethod("CCB");
        data.setRawData(rawBody);
        
        return data;
    }

    // ==================== 私有方法 ====================

    /**
     * 生成MD5签名
     */
    private String generateMD5Signature(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null && !"MAC".equals(entry.getKey())) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }
        sb.append("KEY=").append(merchantKey);

        String signStr = sb.toString();
        log.debug("[建设银行] 待签名字符串：{}", signStr);

        return SecureUtil.md5(signStr).toUpperCase();
    }

    /**
     * 解析建行响应
     */
    private Map<String, String> parseResponse(String response) {
        Map<String, String> map = new LinkedHashMap<>();
        
        // 建行返回格式可能是key=value&key=value
        String[] pairs = response.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        
        return map;
    }

    /**
     * 映射建行交易状态
     */
    private String mapCCBStatus(String ccbStatus) {
        if (ccbStatus == null) {
            return "PENDING";
        }
        switch (ccbStatus) {
            case "Y":
            case "0":
                return "SUCCESS";
            case "N":
            case "1":
                return "FAILED";
            case "2":
                return "PENDING";
            case "3":
                return "REFUNDED";
            default:
                return "PENDING";
        }
    }
}

