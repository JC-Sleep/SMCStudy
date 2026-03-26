package sys.smc.coupon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 积分渠道枚举
 */
@Getter
@AllArgsConstructor
public enum PointsChannel {

    INTERNAL(1, "内部积分", "本公司会员积分"),
    CREDIT_CARD(2, "信用卡积分", "银行信用卡积分"),
    THIRD_PARTY(3, "第三方积分", "其他合作平台积分");

    private final Integer code;
    private final String name;
    private final String description;

    public static PointsChannel fromCode(Integer code) {
        for (PointsChannel channel : values()) {
            if (channel.getCode().equals(code)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unknown PointsChannel code: " + code);
    }
}

