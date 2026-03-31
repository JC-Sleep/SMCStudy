package sys.smc.payment.gateway;

import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.dto.RefundRequest;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.gateway.dto.GatewayPaymentResponse;
import sys.smc.payment.gateway.dto.GatewayRefundResponse;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;

/**
 * 支付网关接口
 * 所有支付渠道（渣打银行、支付宝、建设银行等）都需要实现此接口
 * 
 * @author PaymentSys
 */
public interface PaymentGateway {

    /**
     * 获取支付渠道标识
     * @return 支付渠道枚举
     */
    PaymentChannel getChannel();

    /**
     * 获取渠道名称（中文）
     * @return 渠道名称
     */
    String getChannelName();

    /**
     * 创建支付订单
     * @param request 支付请求
     * @param transactionId 内部交易ID
     * @return 网关响应
     */
    GatewayPaymentResponse createPayment(PaymentInitRequest request, String transactionId);

    /**
     * 查询交易状态
     * @param gatewayTransactionId 网关交易ID
     * @return 交易状态
     */
    GatewayTransactionStatus queryTransactionStatus(String gatewayTransactionId);

    /**
     * 关闭/取消订单
     * @param gatewayTransactionId 网关交易ID
     * @return 是否成功
     */
    boolean closeOrder(String gatewayTransactionId);

    /**
     * 申请退款
     * @param request 退款请求
     * @return 退款响应
     */
    GatewayRefundResponse refund(RefundRequest request);

    /**
     * 验证回调签名
     * @param rawBody 原始请求体
     * @param signature 签名
     * @param headers 请求头
     * @return 是否验证通过
     */
    boolean verifyCallback(String rawBody, String signature, java.util.Map<String, String> headers);

    /**
     * 解析回调数据
     * @param rawBody 原始请求体
     * @return 标准化的回调数据
     */
    sys.smc.payment.dto.PaymentCallbackData parseCallbackData(String rawBody);

    /**
     * 检查渠道是否可用
     * @return 是否可用
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 获取渠道优先级（用于路由选择）
     * @return 优先级，数值越小优先级越高
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 是否支持指定的支付方式
     * @param paymentMethod 支付方式（如：CARD, ALIPAY, WECHAT等）
     * @return 是否支持
     */
    boolean supportsPaymentMethod(String paymentMethod);
}

