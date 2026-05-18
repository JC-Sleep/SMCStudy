package sys.smc.payment.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 用户提交退款申请请求
 */
@Data
public class RefundApplyRequest {

    /** 原始支付交易ID */
    @NotBlank(message = "交易ID不能为空")
    private String transactionId;

    /** 申请退款金额（支持部分退款） */
    @NotNull(message = "退款金额不能为空")
    @DecimalMin(value = "0.01", message = "退款金额必须大于0")
    private BigDecimal refundAmount;

    /** 退款原因（必填，财务审批时查看） */
    @NotBlank(message = "退款原因不能为空")
    private String refundReason;
}
