package sys.smc.payment.exception;

/**
 * 非法支付状态转换异常
 *
 * 当尝试执行未在状态机中定义的转换时抛出。
 * 例如：TIMEOUT → SUCCESS（绕过对账流程），FAILED → REFUNDED（失败订单不能退款）
 *
 * 此异常是业务逻辑拒绝，不是系统异常。
 * 调用方应记录并处理（不应导致系统崩溃）。
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final String fromStatus;
    private final String toStatus;

    public IllegalStateTransitionException(String message) {
        super(message);
        this.fromStatus = null;
        this.toStatus = null;
    }

    public IllegalStateTransitionException(String fromStatus, String toStatus) {
        super(String.format("非法支付状态转换: %s → %s（不在合法转换表中）", fromStatus, toStatus));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public IllegalStateTransitionException(String fromStatus, String toStatus, String reason) {
        super(String.format("非法支付状态转换: %s → %s，原因: %s", fromStatus, toStatus, reason));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }
}

