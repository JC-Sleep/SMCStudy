package sys.smc.payment.enums;

/**
 * 支付渠道枚举
 * 定义所有支持的支付渠道
 */
public enum PaymentChannel {

    /**
     * 渣打银行
     */
    STANDARD_CHARTERED("SCB", "渣打银行", "standard-chartered"),

    /**
     * 支付宝
     */
    ALIPAY("ALIPAY", "支付宝", "alipay"),

    /**
     * 建设银行
     */
    CCB("CCB", "建设银行", "ccb"),

    /**
     * 微信支付
     */
    WECHAT_PAY("WECHAT", "微信支付", "wechat"),

    /**
     * 银联
     */
    UNION_PAY("UNION", "银联", "unionpay");

    /**
     * 渠道代码
     */
    private final String code;

    /**
     * 渠道名称
     */
    private final String name;

    /**
     * 回调路径标识
     */
    private final String callbackPath;

    PaymentChannel(String code, String name, String callbackPath) {
        this.code = code;
        this.name = name;
        this.callbackPath = callbackPath;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCallbackPath() {
        return callbackPath;
    }

    /**
     * 根据代码获取渠道
     */
    public static PaymentChannel fromCode(String code) {
        for (PaymentChannel channel : values()) {
            if (channel.code.equals(code)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("未知的支付渠道代码: " + code);
    }

    /**
     * 根据回调路径获取渠道
     */
    public static PaymentChannel fromCallbackPath(String path) {
        for (PaymentChannel channel : values()) {
            if (channel.callbackPath.equals(path)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("未知的回调路径: " + path);
    }
}

