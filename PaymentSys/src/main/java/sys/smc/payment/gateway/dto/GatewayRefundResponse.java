package sys.smc.payment.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 网关退款响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRefundResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 退款单号（网关返回）
     */
    private String refundNo;

    /**
     * 网关退款ID
     */
    private String gatewayRefundId;

    /**
     * 退款金额
     */
    private BigDecimal refundAmount;

    /**
     * 退款状态
     */
    private String refundStatus;

    /**
     * 退款时间
     */
    private Date refundTime;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 原始响应
     */
    private String rawResponse;
}

