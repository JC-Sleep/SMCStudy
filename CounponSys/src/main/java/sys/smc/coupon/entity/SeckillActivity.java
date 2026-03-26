package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体
 */
@Data
@TableName("T_SECKILL_ACTIVITY")
public class SeckillActivity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 活动ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 活动名称
     */
    private String name;

    /**
     * 活动描述
     */
    private String description;

    /**
     * 关联券模板ID
     */
    private Long templateId;

    /**
     * 活动开始时间
     */
    private LocalDateTime startTime;

    /**
     * 活动结束时间
     */
    private LocalDateTime endTime;

    /**
     * 总库存
     */
    private Integer totalStock;

    /**
     * 剩余库存
     */
    private Integer remainStock;

    /**
     * 每人限抢数量
     */
    private Integer limitPerUser;

    /**
     * 积分渠道 {@link sys.smc.coupon.enums.PointsChannel}
     */
    private Integer pointsChannel;

    /**
     * 所需积分数量
     */
    private Integer requiredPoints;

    /**
     * 所需金额(港币)
     */
    private BigDecimal requiredAmount;

    /**
     * 是否仅限VIP用户 0-否 1-是
     */
    private Integer vipOnly;

    /**
     * VIP等级要求(vipOnly=1时有效)
     */
    private Integer vipLevelRequired;

    /**
     * 活动状态 {@link sys.smc.coupon.enums.SeckillStatus}
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
     * 逻辑删除
     */
    @TableLogic
    private Integer deleted;
}

