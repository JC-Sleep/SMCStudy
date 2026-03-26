package sys.smc.coupon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 秒杀活动状态枚举
 */
@Getter
@AllArgsConstructor
public enum SeckillStatus {

    PENDING(0, "未开始"),
    ONGOING(1, "进行中"),
    PAUSED(2, "已暂停"),
    FINISHED(3, "已结束"),
    SOLD_OUT(4, "已售罄");

    private final Integer code;
    private final String name;

    public static SeckillStatus fromCode(Integer code) {
        for (SeckillStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown SeckillStatus code: " + code);
    }
}

