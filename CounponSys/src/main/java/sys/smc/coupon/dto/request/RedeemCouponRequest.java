package sys.smc.coupon.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 核销优惠券请求
 */
@Data
public class RedeemCouponRequest {

    /**
     * 用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 券码
     */
    @NotBlank(message = "券码不能为空")
    private String couponCode;

    /**
     * 订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 订单原金额
     */
    @NotNull(message = "订单金额不能为空")
    private BigDecimal originalAmount;

    /**
     * 使用场景
     */
    private String useScene;
}

