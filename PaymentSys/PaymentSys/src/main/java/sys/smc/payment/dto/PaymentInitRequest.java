package sys.smc.payment.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付发起请求
 */
@Data
public class PaymentInitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderReference;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户号码
     */
    private String subrNum;

    /**
     * 支付金额
     */
    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额必须大于0")
    private BigDecimal amount;

    /**
     * 货币类型
     */
    private String currency = "HKD";

    /**
     * 支付方式
     */
    private String paymentMethod;

    /**
     * 收银台ID
     */
    private String ecrId;

    /**
     * 客户姓名
     */
    private String customerName;

    /**
     * 客户邮箱
     */
    private String customerEmail;

    /**
     * 返回URL（支付完成后跳转）
     */
    @NotBlank(message = "返回URL不能为空")
    private String returnUrl;

    /**
     * 会话ID
     */
    private String sid;

    /**
     * 幂等性Token（推荐由前端生成UUID传入）
     * 用于分布式环境下防止重复提交
     *
     * 示例：
     * - 前端生成：uuid.v4() 或 Date.now() + Math.random()
     * - 如果不传，后端会使用订单号+支付方式作为幂等键
     */
    private String idempotencyToken;
}
