package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 兑换码发券补偿任务（本地消息表）
 *
 * 解决分布式事务问题：
 *   CAS更新码状态（STATUS: 0→1）+ 写此Task 在同一个DB事务中完成
 *   即使后续 grantCoupon() 失败，此Task记录不会丢失
 *   定时Job扫描 status=0 的Task，自动重试 grantCoupon()
 *   重试超过MAX_RETRY次 → 标记 status=2（人工介入）
 *
 * 为什么选本地消息表而不是Saga/2PC：
 *   Saga回滚：把STATUS从1改回0，但回滚窗口期内用户会看到"已使用"，体验极差
 *   2PC：性能下降30%+，Oracle分布式事务运维复杂
 *   本地消息表：最终一致性，用户几秒内自动收券，体验最好
 */
@Data
@TableName("T_GRANT_TASK")
public class GrantTask implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的兑换码（用于幂等判断和日志追踪） */
    private String redeemCode;

    /** 要发给哪个用户 */
    private String userId;

    /** 要发哪个券模板 */
    private Long templateId;

    /**
     * 任务状态
     * 0=待处理  1=成功  2=失败超限（需人工）  3=人工取消
     */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数（默认3次，超过后人工介入） */
    private Integer maxRetry;

    /** 发券成功后的券实例ID（用于幂等：已有couponId则跳过） */
    private Long couponId;

    /** 最后一次失败原因（便于人工排查） */
    private String failReason;

    /**
     * 下次重试时间（指数退避策略，避免立刻重试DB还在恢复中）
     * 第1次失败 → 1分钟后重试
     * 第2次失败 → 5分钟后重试
     * 第3次失败 → 30分钟后重试 → 超限标记失败，人工告警
     */
    private Date nextRetryTime;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /** 重试间隔（分钟）：第1次1分钟，第2次5分钟，第3次30分钟 */
    public static final int[] RETRY_DELAYS_MINUTES = {1, 5, 30};
}

