package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券发放规则实体
 */
@Data
@TableName("T_COUPON_RULE")
public class CouponRule implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 规则ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 规则名称
     */
    private String name;

    /**
     * 规则类型 1-消费达标 2-套餐绑定 3-定时发放
     */
    private Integer ruleType;

    /**
     * 关联券模板ID
     */
    private Long templateId;

    /**
     * 触发条件(JSON格式)
     */
    private String triggerCondition;

    /**
     * 消费门槛金额
     */
    private BigDecimal thresholdAmount;

    /**
     * 适用套餐编码(逗号分隔)
     */
    private String packageCodes;

    /**
     * 定时发放Cron表达式
     */
    private String cronExpression;

    /**
     * 状态 0-停用 1-启用
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
     * 逻辑删除
     */
    @TableLogic
    private Integer deleted;
}

