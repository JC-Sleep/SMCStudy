package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单实体
 * 记录用户抢购记录
 */
@Data
@TableName("T_SECKILL_ORDER")
public class SeckillOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 秒杀活动ID
     */
    private Long activityId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户手机号
     */
    private String userMobile;

    /**
     * 关联券ID
     */
    private Long couponId;

    /**
     * 积分渠道
     */
    private Integer pointsChannel;

    /**
     * 消耗积分
     */
    private Integer pointsUsed;

    /**
     * 消耗金额
     */
    private BigDecimal amountPaid;

    /**
     * 订单状态 0-待处理 1-成功 2-失败 3-已退款
     */
    private Integer status;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 抢购时间
     */
    private LocalDateTime grabTime;

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
}

