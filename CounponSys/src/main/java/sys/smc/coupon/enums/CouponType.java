package sys.smc.coupon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 优惠券类型枚举
 */
@Getter
@AllArgsConstructor
public enum CouponType {

    CASH(1, "现金券", "直接抵扣金额"),
    DISCOUNT(2, "折扣券", "按比例折扣"),
    FULL_REDUCTION(3, "满减券", "满足条件减免"),
    FREE(4, "免单券", "完全免费"),
    DATA_PACKAGE(5, "流量券", "赠送流量"),
    VOICE_PACKAGE(6, "通话券", "赠送通话分钟");

    private final Integer code;
    private final String name;
    private final String description;

    public static CouponType fromCode(Integer code) {
        for (CouponType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown CouponType code: " + code);
    }
}

