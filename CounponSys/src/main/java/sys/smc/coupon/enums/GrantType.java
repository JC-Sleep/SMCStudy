package sys.smc.coupon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 优惠券发放类型枚举
 */
@Getter
@AllArgsConstructor
public enum GrantType {

    PACKAGE_MONTHLY(1, "套餐月度赠送", "每月固定发放"),
    ACTIVITY(2, "活动营销", "节日/促销活动发放"),
    CONSUMPTION(3, "消费激励", "达到消费门槛自动发放"),
    REFERRAL(4, "推荐奖励", "推荐新用户获得"),
    RETENTION(5, "挽留客户", "合约到期前挽留"),
    SECKILL(6, "秒杀抢购", "限时限量抢购"),
    MANUAL(7, "手动发放", "运营人员手动发放");

    private final Integer code;
    private final String name;
    private final String description;

    public static GrantType fromCode(Integer code) {
        for (GrantType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown GrantType code: " + code);
    }
}

