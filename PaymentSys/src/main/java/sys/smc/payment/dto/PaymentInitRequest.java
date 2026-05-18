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

    /**
     * 渠道强制覆盖（可选）
     *
     * 用途：渣打专属支付页面传 "SCB"，强制走渣打网关，跳过自动路由。
     * 普通信用卡页面不传此字段，由 PaymentGatewayRouter 根据 paymentMethod 自动选 CyberSource。
     *
     * 示例：
     * - 渣打专属页面：{ "channel": "SCB", ... }
     * - 普通信用卡：  { "paymentMethod": "VISA", ... }（不传 channel）
     */
    private String channel;

    /**
     * CyberSource Flex Microform v2 transient token
     *
     * PCI DSS 合规关键字段：前端通过 Flex Microform 录入卡号后，CyberSource 返回此 token。
     * 商户后端将此 token 传给 CyberSource API，真实卡号永远不经过商户服务器。
     *
     * 获取方式：
     * 1. 前端调 GET /api/payment/cybersource/flex-key 获取 captureContext
     * 2. 用 Flex Microform JS SDK 渲染卡号输入框
     * 3. 用户输入卡号后，SDK 回调返回此 transientToken
     * 4. 前端将此 token 放入支付请求的 transientToken 字段
     */
    private String transientToken;
}
