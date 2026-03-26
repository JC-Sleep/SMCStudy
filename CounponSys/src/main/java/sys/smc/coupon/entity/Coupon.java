package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券实例实体
 * 用户持有的具体优惠券
 */
@Data
@TableName("T_COUPON")
public class Coupon implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 券ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 券码(唯一)
     */
    private String couponCode;

    /**
     * 模板ID
     */
    private Long templateId;

    /**
     * 批次ID
     */
    private Long batchId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户手机号
     */
    private String userMobile;

    /**
     * 优惠券类型 {@link sys.smc.coupon.enums.CouponType}
     */
    private Integer couponType;

    /**
     * 面值金额
     */
    private BigDecimal faceValue;

    /**
     * 折扣比例
     */
    private BigDecimal discountRate;

    /**
     * 使用门槛金额
     */
    private BigDecimal thresholdAmount;

    /**
     * 券状态 {@link sys.smc.coupon.enums.CouponStatus}
     */
    private Integer status;

    /**
     * 有效期开始时间
     */
    private LocalDateTime validStartTime;

    /**
     * 有效期结束时间
     */
    private LocalDateTime validEndTime;

    /**
     * 领取时间
     */
    private LocalDateTime claimTime;

    /**
     * 使用时间
     */
    private LocalDateTime useTime;

    /**
     * 使用订单号
     */
    private String orderNo;

    /**
     * 发放类型 {@link sys.smc.coupon.enums.GrantType}
     */
    private Integer grantType;

    /**
     * 发放来源(活动ID/秒杀ID等)
     */
    private String grantSource;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除
     */
    @TableLogic
    private Integer deleted;
}

