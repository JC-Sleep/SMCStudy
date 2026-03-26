package sys.smc.coupon.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券响应DTO
 */
@Data
public class CouponDTO {

    private Long id;
    private String couponCode;
    private Long templateId;
    private String templateName;
    
    /** 券类型名称 */
    private String couponTypeName;
    
    /** 面值金额 */
    private BigDecimal faceValue;
    
    /** 折扣比例 */
    private BigDecimal discountRate;
    
    /** 使用门槛金额 */
    private BigDecimal thresholdAmount;
    
    /** 状态名称 */
    private String statusName;
    
    /** 有效期开始 */
    private LocalDateTime validStartTime;
    
    /** 有效期结束 */
    private LocalDateTime validEndTime;
    
    /** 领取时间 */
    private LocalDateTime claimTime;
    
    /** 使用说明 */
    private String description;
    
    /** 适用范围 */
    private String applicableScope;
}

