package sys.smc.coupon.exception;

import lombok.Getter;

/**
 * 优惠券业务异常
 */
@Getter
public class CouponException extends RuntimeException {

    private final Integer code;

    public CouponException(String message) {
        super(message);
        this.code = 500;
    }

    public CouponException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    // ============ 常见异常静态方法 ============
    
    public static CouponException templateNotFound() {
        return new CouponException(404, "优惠券模板不存在");
    }

    public static CouponException templateDisabled() {
        return new CouponException(400, "优惠券模板已停用");
    }

    public static CouponException stockInsufficient() {
        return new CouponException(400, "优惠券库存不足");
    }

    public static CouponException claimLimitExceeded() {
        return new CouponException(400, "超过每人限领数量");
    }

    public static CouponException dailyLimitExceeded() {
        return new CouponException(400, "超过每日领取限制");
    }

    public static CouponException couponNotFound() {
        return new CouponException(404, "优惠券不存在");
    }

    public static CouponException couponNotAvailable() {
        return new CouponException(400, "优惠券不可用");
    }

    public static CouponException couponExpired() {
        return new CouponException(400, "优惠券已过期");
    }

    public static CouponException couponUsed() {
        return new CouponException(400, "优惠券已使用");
    }

    public static CouponException amountNotMet() {
        return new CouponException(400, "未达到使用门槛金额");
    }
}

