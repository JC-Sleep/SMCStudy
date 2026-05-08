package sys.smc.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 退款申请视图对象（Finance 后台列表/详情展示）
 */
@Data
@Builder
public class RefundApplicationVO {

    private Long applicationId;
    private String transactionId;
    private String orderReference;
    private BigDecimal refundAmount;
    private BigDecimal originalAmount;
    private String applicantUserId;
    private String refundReason;
    private String status;
    private String statusDescription;

    /** 财务审批人 */
    private String reviewedBy;
    private Date reviewedAt;
    private String reviewRemark;

    /** 退款单号（成功后填入） */
    private String refundNo;
    private Date completedAt;
    private String failReason;

    private Date applyTime;
    private Date updateTime;

    /** 已批准/进行中共占用的退款金额（含本申请） */
    private BigDecimal totalLockedAmount;

    /** 该交易已完成的退款次数 */
    private int completedRefundCount;
}

