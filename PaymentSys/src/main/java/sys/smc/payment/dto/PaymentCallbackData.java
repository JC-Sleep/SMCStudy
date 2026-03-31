package sys.smc.payment.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 回调数据（统一格式）
 * 各渠道回调解析后转换为此统一格式
 */
@Data
public class PaymentCallbackData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户ID
     */
    private String merchantId;

    /**
     * 银行/网关交易ID
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
     * 支付状态（统一映射后的状态）
     */
    private String paymentStatus;

    /**
     * 支付时间（Date类型）
     */
    private Date paymentTime;

    /**
     * 支付方式
     */
    private String paymentMethod;

    /**
     * 卡号后4位
     */
    private String cardLast4;

    /**
     * 原始回调数据（用于审计）
     */
    private String rawData;

    /**
     * 渠道特有的额外数据（JSON格式）
     */
    private String extraData;
}
