package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 兑换码批次表
 * 每次生成一批兑换码时创建一条批次记录，便于统计/管理
 */
@Data
@TableName("T_REDEEM_CODE_BATCH")
public class RedeemCodeBatch implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 批次名称（如：2026春节活动-500张） */
    private String batchName;

    /** 关联券模板ID */
    private Long templateId;

    /** 本批次生成数量 */
    private Integer totalCount;

    /** 已兑换数量 */
    private Integer redeemedCount;

    /**
     * 批次状态
     * 0=生成中  1=已激活  2=已停用  3=已过期
     */
    private Integer status;

    /**
     * 同一用户在此批次最多可兑换N张码
     * 默认1张，防止羊毛党持有多张码套取多张优惠券
     * 例如：促销短信活动每人最多兑1张，积分礼品可设为3张
     */
    private Integer maxPerUser;

    /** 批次过期时间 */
    private Date expireTime;

    /** 创建人 */
    private String createBy;

    /** 备注（如：短信活动/包装印刷/积分礼品） */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}

