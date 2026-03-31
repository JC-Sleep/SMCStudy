package sys.smc.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 支付发起响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 交易ID
     */
    private String transactionId;

    /**
     * 支付URL（跳转到银行支付页面）
     */
    private String paymentUrl;

    /**
     * 状态
     */
    private String status;

    /**
     * 支付渠道代码
     */
    private String channel;

    /**
     * 支付渠道名称
     */
    private String channelName;

    /**
     * 过期时间
     */
    private String expiryTime;

    /**
     * 错误消息
     */
    private String errorMessage;
}
