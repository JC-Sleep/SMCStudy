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
     * 退款处理中（中间状态，防止并发重复退款）
     */
    REFUNDING("退款处理中"),

    /**
     * 退款失败（需人工干预）
     */
    REFUND_FAILED("退款失败"),

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

