package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 退款申请实体
 * 对应表：REFUND_APPLICATION
 *
 * 一笔支付交易最多有若干条申请记录（支持多次部分退款）：
 *   - 普通财务批准上限：3次
 *   - 经理无限制
 *   - 所有申请退款金额之和不能超过原始金额
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("REFUND_APPLICATION")
public class RefundApplication extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.INPUT)
    private Long id;

    /** 关联的支付交易ID（内部流水号） */
    @TableField("TRANSACTION_ID")
    private String transactionId;

    /** 订单号（冗余字段，方便查询） */
    @TableField("ORDER_REFERENCE")
    private String orderReference;

    /** 支付渠道代码（冗余，执行时使用） */
    @TableField("GATEWAY_CODE")
    private String gatewayCode;

    /** 申请退款金额 */
    @TableField("REFUND_AMOUNT")
    private BigDecimal refundAmount;

    /** 原始支付金额（冗余，用于校验上限） */
    @TableField("ORIGINAL_AMOUNT")
    private BigDecimal originalAmount;

    /** 申请人用户ID */
    @TableField("APPLICANT_USER_ID")
    private String applicantUserId;

    /** 退款原因 */
    @TableField("REFUND_REASON")
    private String refundReason;

    /**
     * 申请状态：PENDING_REVIEW / APPROVED / REJECTED / EXECUTING / COMPLETED / FAILED
     */
    @TableField("STATUS")
    private String status;

    /** 财务审批人用户ID */
    @TableField("REVIEWED_BY")
    private String reviewedBy;

    /** 财务审批时间 */
    @TableField("REVIEWED_AT")
    private Date reviewedAt;

    /** 财务审批备注 */
    @TableField("REVIEW_REMARK")
    private String reviewRemark;

    /** 实际执行的退款单号（执行成功后填入） */
    @TableField("REFUND_NO")
    private String refundNo;

    /** 退款完成时间 */
    @TableField("COMPLETED_AT")
    private Date completedAt;

    /** 失败原因 */
    @TableField("FAIL_REASON")
    private String failReason;

    /** 创建用户 */
    @TableField("CREATE_USER")
    private String createUser;

    /** 更新用户 */
    @TableField("UPDATE_USER")
    private String updateUser;
}
