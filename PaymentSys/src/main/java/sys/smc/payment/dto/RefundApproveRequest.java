package sys.smc.payment.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 财务审批/拒绝退款申请请求
 */
@Data
public class RefundApproveRequest {

    /** 退款申请ID */
    @NotNull(message = "申请ID不能为空")
    private Long applicationId;

    /**
     * 审批备注（拒绝时必须说明原因）
     * 批准时可填写确认信息，如："金额核实无误，批准退款"
     */
    private String reviewRemark;
}
