package sys.smc.coupon.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动响应DTO
 */
@Data
public class SeckillActivityDTO {

    private Long id;
    private String name;
    private String description;
    
    /** 关联券模板名称 */
    private String templateName;
    
    /** 券面值 */
    private BigDecimal couponFaceValue;
    
    /** 活动开始时间 */
    private LocalDateTime startTime;
    
    /** 活动结束时间 */
    private LocalDateTime endTime;
    
    /** 总库存 */
    private Integer totalStock;
    
    /** 剩余库存 */
    private Integer remainStock;
    
    /** 每人限抢 */
    private Integer limitPerUser;
    
    /** 积分渠道 */
    private String pointsChannelName;
    
    /** 所需积分 */
    private Integer requiredPoints;
    
    /** 所需金额 */
    private BigDecimal requiredAmount;
    
    /** 是否VIP专属 */
    private Boolean vipOnly;
    
    /** 活动状态 */
    private String statusName;
    
    /** 距离开始秒数(未开始时) */
    private Long secondsToStart;
}

