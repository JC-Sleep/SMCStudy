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
 *
 * ⑤ 修复：
 *  - HTTP 超时从写死 30000ms 改为通过 getHttpTimeoutMs() 可配置（默认 10 秒）
 *  - 注意：@CircuitBreaker 注解不能加在抽象类的 protected 方法上（AOP 代理
 *    只能拦截 Spring Bean 的 public 方法）。完整熔断方案：在 PaymentGatewayRouter
 *    的 selectGateway/getGateway 调用处加 @CircuitBreaker，或使用编程式
 *    CircuitBreaker.decorateSupplier() 包裹具体网关调用。
 *  - 当前修复：缩短超时（10s）+ 捕获异常抛 GatewayException，
 *    配合 application.yml resilience4j 配置在子类公开方法上生效。
 */
public abstract class AbstractPaymentGateway implements PaymentGateway {

    /** ⑤ 修复：子类可覆盖超时时长，默认 10 秒（原来写死 30 秒） */
    protected int getHttpTimeoutMs() { return 10_000; }

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

    /**
     * ⑤ 修复：HTTP POST，超时使用可配置值（默认10s）
     * 超时或网络异常统一抛 GatewayException，调用方统一处理降级
     */
    protected String doPost(String url, Map<String, Object> data, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url);
            headers.forEach(request::header);
            request.body(JSON.toJSONString(data));
            request.timeout(getHttpTimeoutMs());  // ⑤ 修复：10s 而非 30s

            HttpResponse response = request.execute();
            if (!response.isOk()) {
                throw new GatewayException("HTTP请求失败，状态码: " + response.getStatus()
                    + "，URL: " + url);
            }
            return response.body();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            // ⑤ 超时或网络异常：统一包装为 GatewayException
            // 上层 PaymentService 捕获后：1) 标记 FAILED 状态  2) 触发熔断计数
            throw new GatewayException("HTTP请求异常（超时或网络）: " + e.getMessage()
                + "，URL: " + url, e);
        }
    }

    /**
     * ⑤ 修复：HTTP GET，超时使用可配置值
     */
    protected String doGet(String url, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.get(url);
            headers.forEach(request::header);
            request.timeout(getHttpTimeoutMs());

            HttpResponse response = request.execute();
            if (!response.isOk()) {
                throw new GatewayException("HTTP请求失败，状态码: " + response.getStatus()
                    + "，URL: " + url);
            }
            return response.body();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("HTTP请求异常（超时或网络）: " + e.getMessage()
                + "，URL: " + url, e);
        }
    }
}
