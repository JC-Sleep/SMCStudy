package sys.smc.payment.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 回调数据
 */
@Data
public class PaymentCallbackData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户ID
     */
    private String merchantId;

    /**
     * 银行交易ID
     */
    private String gatewayTransactionId;

    /**
     * 订单编号
     */
    private String orderReference;

    /**
     * 金额
     */
    private BigDecimal amount;

    /**
     * 货币
     */
    private String currency;

    /**
     * 支付状态
     */
    private String paymentStatus;

    /**
     * 支付时间
     */
    private String paymentTime;

    /**
     * 支付方式
     */
    private String paymentMethod;

    /**
     * 卡号后4位
     */
    private String cardLast4;
}

