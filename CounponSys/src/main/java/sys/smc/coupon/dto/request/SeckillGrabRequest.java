package sys.smc.coupon.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 秒杀抢券请求
 */
@Data
public class SeckillGrabRequest {

    /**
     * 秒杀活动ID
     */
    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    /**
     * 用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 用户手机号
     */
    private String userMobile;

    /**
     * 积分渠道(1-内部积分, 2-信用卡积分, 3-第三方积分)
     */
    private Integer pointsChannel;

    /**
     * 第三方积分Token(对接外部积分平台时使用)
     */
    private String thirdPartyToken;

    /**
     * 用户VIP等级(用于VIP专属活动校验)
     */
    private Integer vipLevel;
}

