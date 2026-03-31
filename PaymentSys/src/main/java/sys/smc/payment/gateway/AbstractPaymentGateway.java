package sys.smc.payment.gateway;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.exception.GatewayException;
import sys.smc.payment.gateway.dto.GatewayPaymentResponse;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付网关抽象基类
 * 提供通用方法实现，子类只需要实现特定渠道的逻辑
 */
public abstract class AbstractPaymentGateway implements PaymentGateway {

    protected abstract String getApiEndpoint();
    protected abstract String getApiKey();
    protected abstract String getApiSecret();
    protected abstract String getCallbackUrl();
    protected abstract String buildSignature(Map<String, Object> data);
    protected abstract Logger getLogger();
    protected abstract Map<String, Object> buildPaymentRequest(PaymentInitRequest request, String transactionId);
    protected abstract GatewayPaymentResponse parsePaymentResponse(String responseBody);
    protected abstract GatewayTransactionStatus parseStatusResponse(String responseBody);

    protected Map<String, String> buildHeaders(String signature) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-API-Key", getApiKey());
        headers.put("X-Signature", signature);
        return headers;
    }

    @Override
    public boolean isAvailable() {
        return getApiEndpoint() != null && !getApiEndpoint().isEmpty()
            && getApiKey() != null && !getApiKey().isEmpty();
    }

    protected String doPost(String url, Map<String, Object> data, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
            request.body(JSON.toJSONString(data));
            request.timeout(30000);
            
            HttpResponse response = request.execute();
            if (!response.isOk()) {
                throw new GatewayException("HTTP请求失败，状态码: " + response.getStatus());
            }
            return response.body();
        } catch (Exception e) {
            throw new GatewayException("HTTP请求异常: " + e.getMessage(), e);
        }
    }

    protected String doGet(String url, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.get(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
            request.timeout(30000);
            
            HttpResponse response = request.execute();
            if (!response.isOk()) {
                throw new GatewayException("HTTP请求失败，状态码: " + response.getStatus());
            }
            return response.body();
        } catch (Exception e) {
            throw new GatewayException("HTTP请求异常: " + e.getMessage(), e);
        }
    }
}

