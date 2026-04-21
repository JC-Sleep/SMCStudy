package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 优惠券兑换码实体
 * 用途：线下/线上活动发放的兑换码（如促销短信、包装印刷码、积分礼品码）
 * 用户输入code → 系统校验 → 发放优惠券到账户
 *
 * 防刷设计：
 *  1. HMAC签名校验（无效暴力枚举码）
 *  2. DB唯一约束 + CAS原子更新状态（防重复兑换）
 *  3. Redis分布式锁（防并发重入）
 *  4. IP/User限速（防暴力尝试）
 *  5. 失败次数上限（超限自动锁定）
 */
@Data
@TableName("T_COUPON_REDEEM_CODE")
public class CouponRedeemCode implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 兑换码（格式：PREFIX-BASE36ID-HMAC4） 示例：CP-2K5XM1-A3F7 */
    private String code;

    /** 所属批次ID */
    private Long batchId;

    /** 关联券模板ID */
    private Long templateId;

    /**
     * 状态
     * 0=未使用  1=已使用  2=已过期  3=已锁定（失败次数超限）
     */
    private Integer status;

    /** 兑换用户ID（兑换后写入） */
    private String userId;

    /** 兑换时间 */
    private Date redeemTime;

    /** 兑换来源IP */
    private String redeemIp;

    /** 兑换渠道: APP / WEB / H5 / SMS / ADMIN */
    private String redeemChannel;

    /** 兑换失败次数（超过MAX_FAIL_COUNT自动锁定） */
    private Integer failCount;

    /**
     * 已解锁次数
     * >= 2次：强制人工审核，防止攻击者"社工客服反复解锁→暴力枚举"
     * 关键：STATUS=1（已成功兑换）的码绝对禁止解锁
     */
    private Integer unlockCount;

    /** 兑换码过期时间 */
    private Date expireTime;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;

    /** 最大允许失败次数，超过后自动锁定该码 */
    public static final int MAX_FAIL_COUNT = 5;
}

