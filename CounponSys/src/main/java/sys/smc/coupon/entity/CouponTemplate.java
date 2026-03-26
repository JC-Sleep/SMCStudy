package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板实体
 * 定义券的基本属性模板
 */
@Data
@TableName("T_COUPON_TEMPLATE")
public class CouponTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 模板ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 模板名称
     */
    private String name;

    /**
     * 模板描述
     */
    private String description;

    /**
     * 优惠券类型 {@link sys.smc.coupon.enums.CouponType}
     */
    private Integer couponType;

    /**
     * 发放类型 {@link sys.smc.coupon.enums.GrantType}
     */
    private Integer grantType;

    /**
     * 面值金额(现金券/满减券使用)
     */
    private BigDecimal faceValue;

    /**
     * 折扣比例(折扣券使用,如0.8表示8折)
     */
    private BigDecimal discountRate;

    /**
     * 使用门槛金额(满多少可用)
     */
    private BigDecimal thresholdAmount;

    /**
     * 适用套餐类型(逗号分隔,空表示全部适用)
     */
    private String applicablePackages;

    /**
     * 适用服务类型(逗号分隔,空表示全部适用)
     */
    private String applicableServices;

    /**
     * 是否可叠加使用 0-否 1-是
     */
    private Integer stackable;

    /**
     * 有效期类型 1-绝对时间 2-相对时间(领取后N天)
     */
    private Integer validityType;

    /**
     * 绝对有效期-开始时间
     */
    private LocalDateTime validStartTime;

    /**
     * 绝对有效期-结束时间
     */
    private LocalDateTime validEndTime;

    /**
     * 相对有效期-天数
     */
    private Integer validDays;

    /**
     * 总发行量
     */
    private Integer totalQuantity;

    /**
     * 已发放数量
     */
    private Integer issuedQuantity;

    /**
     * 每人限领数量
     */
    private Integer limitPerUser;

    /**
     * 模板状态 0-草稿 1-启用 2-停用
     */
    private Integer status;

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
     * 创建人
     */
    private String createBy;

    /**
     * 逻辑删除 0-未删除 1-已删除
     */
    @TableLogic
    private Integer deleted;
}

