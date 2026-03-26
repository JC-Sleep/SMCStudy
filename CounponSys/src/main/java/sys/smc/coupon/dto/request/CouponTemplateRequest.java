package sys.smc.coupon.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建/更新优惠券模板请求
 */
@Data
public class CouponTemplateRequest {

    /**
     * 模板ID(更新时必填)
     */
    private Long id;

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    private String name;

    /**
     * 模板描述
     */
    private String description;

    /**
     * 优惠券类型 1现金券 2折扣券 3满减券 4免单券 5流量券 6通话券
     */
    @NotNull(message = "优惠券类型不能为空")
    private Integer couponType;

    /**
     * 发放类型 1套餐月度 2活动营销 3消费激励 4推荐奖励 5挽留客户 6秒杀抢购 7手动发放
     */
    @NotNull(message = "发放类型不能为空")
    private Integer grantType;

    /**
     * 面值金额(现金券/满减券)
     */
    private BigDecimal faceValue;

    /**
     * 折扣比例(折扣券,如0.8表示8折)
     */
    private BigDecimal discountRate;

    /**
     * 使用门槛金额(满多少可用)
     */
    private BigDecimal thresholdAmount;

    /**
     * 适用套餐类型(逗号分隔,空表示全部)
     */
    private String applicablePackages;

    /**
     * 适用服务类型(逗号分隔,空表示全部)
     */
    private String applicableServices;

    /**
     * 是否可叠加使用 0-否 1-是
     */
    private Integer stackable = 0;

    /**
     * 有效期类型 1-绝对时间 2-相对时间(领取后N天)
     */
    @NotNull(message = "有效期类型不能为空")
    private Integer validityType;

    /**
     * 绝对有效期-开始时间(validityType=1时必填)
     */
    private LocalDateTime validStartTime;

    /**
     * 绝对有效期-结束时间(validityType=1时必填)
     */
    private LocalDateTime validEndTime;

    /**
     * 相对有效期-天数(validityType=2时必填)
     */
    private Integer validDays;

    /**
     * 总发行量
     */
    @NotNull(message = "总发行量不能为空")
    private Integer totalQuantity;

    /**
     * 每人限领数量
     */
    private Integer limitPerUser = 1;
}

