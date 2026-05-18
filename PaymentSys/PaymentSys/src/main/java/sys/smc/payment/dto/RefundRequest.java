package sys.smc.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 退款请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    /**
     * 原交易ID
     */
    @NotBlank(message = "原交易ID不能为空")
    private String transactionId;

    /**
     * 网关交易ID
     */
    private String gatewayTransactionId;

    /**
     * 退款金额
     */
    @NotNull(message = "退款金额不能为空")
    @DecimalMin(value = "0.01", message = "退款金额必须大于0")
    private BigDecimal refundAmount;

    /**
     * 原交易金额
     */
    private BigDecimal originalAmount;

    /**
     * 退款原因
     */
    @NotBlank(message = "退款原因不能为空")
    private String refundReason;

    /**
     * 退款单号（内部生成）
     */
    private String refundNo;

    /**
     * 操作员
     */
    private String operator;

    /**
     * 支付渠道
     */
    private String channel;
}

