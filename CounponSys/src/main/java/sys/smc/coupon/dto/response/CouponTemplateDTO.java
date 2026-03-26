package sys.smc.coupon.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板响应DTO
 */
@Data
public class CouponTemplateDTO {

    private Long id;
    
    private String name;
    
    private String description;
    
    /** 券类型 */
    private Integer couponType;
    private String couponTypeName;
    
    /** 发放类型 */
    private Integer grantType;
    private String grantTypeName;
    
    /** 面值金额 */
    private BigDecimal faceValue;
    
    /** 折扣比例 */
    private BigDecimal discountRate;
    
    /** 使用门槛 */
    private BigDecimal thresholdAmount;
    
    /** 适用套餐 */
    private String applicablePackages;
    
    /** 适用服务 */
    private String applicableServices;
    
    /** 是否可叠加 */
    private Boolean stackable;
    
    /** 有效期类型 */
    private Integer validityType;
    private String validityTypeName;
    
    /** 有效期开始 */
    private LocalDateTime validStartTime;
    
    /** 有效期结束 */
    private LocalDateTime validEndTime;
    
    /** 相对有效天数 */
    private Integer validDays;
    
    /** 总发行量 */
    private Integer totalQuantity;
    
    /** 已发放数量 */
    private Integer issuedQuantity;
    
    /** 剩余数量 */
    private Integer remainQuantity;
    
    /** 每人限领 */
    private Integer limitPerUser;
    
    /** 状态 */
    private Integer status;
    private String statusName;
    
    /** 创建时间 */
    private LocalDateTime createTime;
    
    /** 创建人 */
    private String createBy;
}

