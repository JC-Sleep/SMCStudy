package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀订单消费重试任务（2026-04-22 新增）
 *
 * 解决问题：Kafka消费失败时，临时故障（DB抖动、积分服务超时）
 *           不应直接标记订单失败，应该等一会儿重试，保护用户权益。
 *
 * 工作流程：
 *   消费失败(临时异常) → 写此表(status=0) → Job每30s扫描到期任务 → 重试
 *   重试成功 → status=1，订单status=1，用户收到券
 *   重试3次仍失败 → status=2，订单status=2，发死信Topic，人工告警
 *
 * 指数退避间隔：
 *   第1次失败 → 1分钟后重试
 *   第2次失败 → 5分钟后重试
 *   第3次失败 → 30分钟后重试 → 超限进死信队列
 */
@Data
@TableName("T_SECKILL_RETRY_TASK")
public class SeckillRetryTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的秒杀订单ID */
    private Long orderId;

    /** 关联活动ID */
    private Long activityId;

    /** 关联用户ID（日志和告警用） */
    private String userId;

    /**
     * 任务状态
     * 0=待重试  1=重试成功  2=失败超限（需人工处理）
     */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数（默认3） */
    private Integer maxRetry;

    /** 最后一次失败原因 */
    private String failReason;

    /** 下次重试时间（指数退避计算） */
    private LocalDateTime nextRetryTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ── 常量 ──

    /** 指数退避延迟（分钟）：第1次1分钟，第2次5分钟，第3次30分钟 */
    public static final int[] RETRY_DELAYS_MINUTES = {1, 5, 30};

    /** 最大重试次数 */
    public static final int MAX_RETRY_COUNT = 3;
}

