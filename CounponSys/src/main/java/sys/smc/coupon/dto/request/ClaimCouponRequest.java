package sys.smc.coupon.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 领取优惠券请求
 */
@Data
public class ClaimCouponRequest {

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
     * 模板ID
     */
    @NotNull(message = "模板ID不能为空")
    private Long templateId;
}

