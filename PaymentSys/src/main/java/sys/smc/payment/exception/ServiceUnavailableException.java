package sys.smc.payment.exception;

/**
 * 服务不可用异常（熔断触发）
 *
 * 当支付渠道被 GatewayHealthMonitor 熔断时抛出。
 * 对应 HTTP 503 场景：前端应展示"当前该支付方式暂时不可用，请稍后重试"
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String channelCode;

    public ServiceUnavailableException(String message) {
        super(message);
        this.channelCode = null;
    }

    public ServiceUnavailableException(String channelCode, String message) {
        super(message);
        this.channelCode = channelCode;
    }

    public String getChannelCode() {
        return channelCode;
    }
}

