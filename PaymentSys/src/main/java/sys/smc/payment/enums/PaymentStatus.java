package sys.smc.payment.enums;

/**
 * 支付状态枚举
 */
public enum PaymentStatus {

    /**
     * 初始化
     */
    INIT("初始化"),

    /**
     * 待处理
     */
    PENDING("待处理"),

    /**
     * 成功
     */
    SUCCESS("成功"),

    /**
     * 失败
     */
    FAILED("失败"),

    /**
     * 超时
     */
    TIMEOUT("超时"),

    /**
     * 对账中
     */
    RECONCILING("对账中"),

    /**
     * 已退款
     */
    REFUNDED("已退款");

    private final String description;

    PaymentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

