package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 回调日志实体
 * 对应表：PAYMENT_CALLBACK_LOG
 */
@Data
@TableName("PAYMENT_CALLBACK_LOG")
public class PaymentCallbackLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "ID", type = IdType.INPUT)
    private Long id;

    /**
     * 交易ID
     */
    @TableField("TRANSACTION_ID")
    private String transactionId;

    /**
     * 回调时间
     */
    @TableField("CALLBACK_TIME")
    private Date callbackTime;

    /**
     * 回调URL
     */
    @TableField("CALLBACK_URL")
    private String callbackUrl;

    /**
     * 请求方法（POST/GET）
     */
    @TableField("REQUEST_METHOD")
    private String requestMethod;

    /**
     * 请求头（JSON格式）
     */
    @TableField("REQUEST_HEADERS")
    private String requestHeaders;

    /**
     * 请求体（JSON格式）
     */
    @TableField("REQUEST_BODY")
    private String requestBody;

    /**
     * 请求IP
     */
    @TableField("REQUEST_IP")
    private String requestIp;

    /**
     * 处理状态（SUCCESS, FAILED, DUPLICATE）
     */
    @TableField("PROCESSING_STATUS")
    private String processingStatus;

    /**
     * 处理耗时（毫秒）
     */
    @TableField("PROCESSING_TIME_MS")
    private Integer processingTimeMs;

    /**
     * 错误信息
     */
    @TableField("ERROR_MESSAGE")
    private String errorMessage;

    /**
     * 签名是否有效（0=否, 1=是）
     */
    @TableField("SIGNATURE_VALID")
    private Integer signatureValid;

    /**
     * 签名值
     */
    @TableField("SIGNATURE_VALUE")
    private String signatureValue;

    /**
     * 网关状态
     */
    @TableField("GATEWAY_STATUS")
    private String gatewayStatus;

    /**
     * 网关消息
     */
    @TableField("GATEWAY_MESSAGE")
    private String gatewayMessage;

    /**
     * 网关交易ID
     */
    @TableField("GATEWAY_TRANSACTION_ID")
    private String gatewayTransactionId;

    /**
     * 创建时间
     */
    @TableField("CREATE_TIME")
    private Date createTime;
}

