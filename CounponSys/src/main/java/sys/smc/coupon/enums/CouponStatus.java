package sys.smc.coupon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 优惠券状态枚举
 */
@Getter
@AllArgsConstructor
public enum CouponStatus {

    AVAILABLE(1, "可用"),
    USED(2, "已使用"),
    EXPIRED(3, "已过期"),
    LOCKED(4, "已锁定"),
    CANCELLED(5, "已取消");

    private final Integer code;
    private final String name;

    public static CouponStatus fromCode(Integer code) {
        for (CouponStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown CouponStatus code: " + code);
    }
}

