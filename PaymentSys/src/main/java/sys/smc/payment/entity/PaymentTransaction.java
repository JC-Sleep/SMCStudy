package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 支付交易实体
 * 对应表：PAYMENT_TRANSACTION
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("PAYMENT_TRANSACTION")
public class PaymentTransaction extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "ID", type = IdType.INPUT)
    private Long id;

    /**
     * 内部交易ID
     */
    @TableField("TRANSACTION_ID")
    private String transactionId;

    /**
     * 幂等键（订单号+金额+时间戳）
     */
    @TableField("IDEMPOTENCY_KEY")
    private String idempotencyKey;

    /**
     * 订单编号（如：CSP001xxxxx）
     */
    @TableField("ORDER_REFERENCE")
    private String orderReference;

    /**
     * 用户ID
     */
    @TableField("USER_ID")
    private String userId;

    /**
     * 用户号码
     */
    @TableField("SUBR_NUM")
    private String subrNum;

    /**
     * 客户姓名
     */
    @TableField("CUSTOMER_NAME")
    private String customerName;

    /**
     * 支付金额
     */
    @TableField("AMOUNT")
    private BigDecimal amount;

    /**
     * 货币类型（默认HKD）
     */
    @TableField("CURRENCY")
    private String currency;

    /**
     * 支付方式（CARD, ALIPAY等）
     */
    @TableField("PAYMENT_METHOD")
    private String paymentMethod;

    /**
     * 网关名称（默认STANDARD_CHARTERED）
     */
    @TableField("GATEWAY_NAME")
    private String gatewayName;

    /**
     * 银行交易ID
     */
    @TableField("GATEWAY_TRANSACTION_ID")
    private String gatewayTransactionId;

    /**
     * 银行订单号
     */
    @TableField("GATEWAY_ORDER_NO")
    private String gatewayOrderNo;

    /**
     * 支付状态（INIT, PENDING, SUCCESS, FAILED, TIMEOUT, REFUNDED）
     */
    @TableField("PAYMENT_STATUS")
    private String paymentStatus;

    /**
     * 前一个状态（用于审计）
     */
    @TableField("PREVIOUS_STATUS")
    private String previousStatus;

    /**
     * 状态更新时间
     */
    @TableField("STATUS_UPDATE_TIME")
    private Date statusUpdateTime;

    /**
     * 收银台ID（如：W30000000718xxxx）
     */
    @TableField("ECR_ID")
    private String ecrId;

    /**
     * 会话ID（如：cs_acq）
     */
    @TableField("SID")
    private String sid;

    /**
     * 环境（PROD, UAT, DEV）
     */
    @TableField("ENVIRONMENT")
    private String environment;

    /**
     * 是否收到回调（0=否, 1=是）
     */
    @TableField("CALLBACK_RECEIVED")
    private Integer callbackReceived;

    /**
     * 回调次数
     */
    @TableField("CALLBACK_COUNT")
    private Integer callbackCount;

    /**
     * 最后回调时间
     */
    @TableField("LAST_CALLBACK_TIME")
    private Date lastCallbackTime;

    /**
     * 对账状态（MATCHED, MISMATCH, PENDING）
     */
    @TableField("RECONCILIATION_STATUS")
    private String reconciliationStatus;

    /**
     * 对账时间
     */
    @TableField("RECONCILIATION_TIME")
    private Date reconciliationTime;

    /**
     * 最后查询银行状态时间
     */
    @TableField("LAST_QUERY_TIME")
    private Date lastQueryTime;

    /**
     * 创建用户
     */
    @TableField("CREATE_USER")
    private String createUser;

    /**
     * 更新用户
     */
    @TableField("UPDATE_USER")
    private String updateUser;

    /**
     * 备注
     */
    @TableField("REMARKS")
    private String remarks;

    /**
     * 错误信息
     */
    @TableField("ERROR_MESSAGE")
    private String errorMessage;
}

