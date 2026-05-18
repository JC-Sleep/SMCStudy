package sys.smc.payment.gateway.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 网关支付响应
 */
@Data
public class GatewayPaymentResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 状态
     */
    private String status;

    /**
     * 消息
     */
    private String message;

    /**
     * 银行交易ID
     */
    private String transactionId;

    /**
     * 银行订单号
     */
    private String orderNo;

    /**
     * 支付URL
     */
    private String paymentUrl;
}

