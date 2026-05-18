package sys.smc.payment.gateway.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 网关交易状态
 */
@Data
public class GatewayTransactionStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 交易ID
     */
    private String transactionId;

    /**
     * 状态
     */
    private String status;

    /**
     * 消息
     */
    private String message;

    /**
     * 支付时间
     */
    private String paymentTime;
}

