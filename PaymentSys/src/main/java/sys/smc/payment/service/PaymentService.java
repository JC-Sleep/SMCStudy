package sys.smc.payment.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.dto.PaymentInitRequest;
import sys.smc.payment.dto.PaymentInitResponse;
import sys.smc.payment.dto.RefundRequest;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.PaymentException;
import sys.smc.payment.gateway.PaymentGateway;
import sys.smc.payment.gateway.PaymentGatewayRouter;
import sys.smc.payment.gateway.dto.GatewayPaymentResponse;
import sys.smc.payment.gateway.dto.GatewayRefundResponse;
import sys.smc.payment.mapper.PaymentTransactionMapper;

import java.util.Date;

/**
 * 支付服务
 * 支持多渠道支付：渣打银行、支付宝、建设银行等
 */
@Service
@Slf4j
public class PaymentService {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private PaymentGatewayRouter gatewayRouter;

    @Value("${payment.timeout.threshold:300}")
    private Integer timeoutThreshold;

    /**
     * 发起支付（自动选择渠道）
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        // 根据支付方式自动选择网关
        PaymentGateway gateway = gatewayRouter.selectGateway(request.getPaymentMethod());
        return doInitiatePayment(request, gateway);
    }

    /**
     * 发起支付（指定渠道）
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentInitResponse initiatePayment(PaymentInitRequest request, String channelCode) {
        PaymentGateway gateway = gatewayRouter.getGateway(channelCode);
        return doInitiatePayment(request, gateway);
    }

    /**
     * 发起支付（指定渠道枚举）
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentInitResponse initiatePayment(PaymentInitRequest request, PaymentChannel channel) {
        PaymentGateway gateway = gatewayRouter.getGateway(channel);
        return doInitiatePayment(request, gateway);
    }

    /**
     * 执行支付发起
     */
    private PaymentInitResponse doInitiatePayment(PaymentInitRequest request, PaymentGateway gateway) {
        log.info("开始处理支付请求，订单号：{}，渠道：{}", 
            request.getOrderReference(), gateway.getChannelName());

        // 1. 生成幂等键
        String idempotencyKey = generateIdempotencyKey(request);

        // 2. 检查交易是否已存在（幂等性）
        PaymentTransaction existing = transactionMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            log.warn("检测到重复支付请求，幂等键：{}", idempotencyKey);
            return buildResponseFromTransaction(existing);
        }

        // 3. 创建INIT状态的交易记录
        PaymentTransaction transaction = buildTransaction(request, idempotencyKey, gateway.getChannel());
        transaction.setPaymentStatus(PaymentStatus.INIT.name());
        transaction.setId(getNextId());
        transactionMapper.insert(transaction);

        try {
            // 4. 调用网关API
            log.info("调用 {} API，交易ID：{}", gateway.getChannelName(), transaction.getTransactionId());
            GatewayPaymentResponse gatewayResponse = gateway.createPayment(request, transaction.getTransactionId());

            // 5. 更新交易信息
            transaction.setGatewayTransactionId(gatewayResponse.getTransactionId());
            transaction.setGatewayOrderNo(gatewayResponse.getOrderNo());
            transaction.setPaymentStatus(PaymentStatus.PENDING.name());
            transaction.setStatusUpdateTime(new Date());
            transactionMapper.updateById(transaction);

            log.info("支付发起成功，交易ID：{}，网关交易ID：{}",
                transaction.getTransactionId(), gatewayResponse.getTransactionId());

            // 6. 返回响应给客户端
            return PaymentInitResponse.builder()
                .transactionId(transaction.getTransactionId())
                .paymentUrl(gatewayResponse.getPaymentUrl())
                .status(PaymentStatus.PENDING.name())
                .channel(gateway.getChannel().getCode())
                .channelName(gateway.getChannelName())
                .expiryTime(DateUtil.formatDateTime(DateUtil.offsetSecond(new Date(), timeoutThreshold)))
                .build();

        } catch (Exception e) {
            log.error("支付发起失败，订单号：{}，渠道：{}", 
                request.getOrderReference(), gateway.getChannelName(), e);
            transaction.setPaymentStatus(PaymentStatus.FAILED.name());
            transaction.setErrorMessage(e.getMessage());
            transaction.setStatusUpdateTime(new Date());
            transactionMapper.updateById(transaction);
            throw new PaymentException("支付发起失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询支付状态
     */
    public PaymentTransaction queryPaymentStatus(String transactionId) {
        return transactionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentTransaction>()
                .eq(PaymentTransaction::getTransactionId, transactionId)
        );
    }

    /**
     * 根据订单号查询
     */
    public PaymentTransaction queryByOrderReference(String orderReference) {
        return transactionMapper.selectByOrderReference(orderReference);
    }

    /**
     * 退款
     */
    @Transactional(rollbackFor = Exception.class)
    public GatewayRefundResponse refund(RefundRequest request) {
        // 查询原交易
        PaymentTransaction transaction = transactionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentTransaction>()
                .eq(PaymentTransaction::getTransactionId, request.getTransactionId())
        );

        if (transaction == null) {
            throw new PaymentException("原交易不存在");
        }

        if (!PaymentStatus.SUCCESS.name().equals(transaction.getPaymentStatus())) {
            throw new PaymentException("只有支付成功的交易才能退款");
        }

        // 获取对应渠道的网关
        PaymentChannel channel = PaymentChannel.fromCode(transaction.getGatewayName());
        PaymentGateway gateway = gatewayRouter.getGateway(channel);

        // 设置网关交易ID
        request.setGatewayTransactionId(transaction.getGatewayTransactionId());
        request.setRefundNo("RF" + IdUtil.getSnowflakeNextIdStr());

        // 调用网关退款
        GatewayRefundResponse response = gateway.refund(request);

        // 更新交易状态
        if (response.isSuccess()) {
            transaction.setPreviousStatus(transaction.getPaymentStatus());
            transaction.setPaymentStatus(PaymentStatus.REFUNDED.name());
            transaction.setStatusUpdateTime(new Date());
            transactionMapper.updateById(transaction);
        }

        return response;
    }

    /**
     * 生成幂等键
     */
    private String generateIdempotencyKey(PaymentInitRequest request) {
        return request.getOrderReference() + "_" +
               request.getAmount().toString() + "_" +
               System.currentTimeMillis();
    }

    /**
     * 构建交易对象
     */
    private PaymentTransaction buildTransaction(PaymentInitRequest request, 
                                                 String idempotencyKey, 
                                                 PaymentChannel channel) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTransactionId("TXN" + IdUtil.getSnowflakeNextIdStr());
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setOrderReference(request.getOrderReference());
        transaction.setUserId(request.getUserId());
        transaction.setSubrNum(request.getSubrNum());
        transaction.setCustomerName(request.getCustomerName());
        transaction.setAmount(request.getAmount());
        transaction.setCurrency(request.getCurrency());
        transaction.setPaymentMethod(request.getPaymentMethod());
        transaction.setGatewayName(channel.getCode());  // 使用渠道代码
        transaction.setEcrId(request.getEcrId());
        transaction.setSid(request.getSid());
        transaction.setEnvironment("PROD");
        transaction.setCallbackReceived(0);
        transaction.setCallbackCount(0);
        transaction.setCreateUser("SYSTEM");
        transaction.setUpdateUser("SYSTEM");
        return transaction;
    }

    /**
     * 从交易构建响应
     */
    private PaymentInitResponse buildResponseFromTransaction(PaymentTransaction transaction) {
        return PaymentInitResponse.builder()
            .transactionId(transaction.getTransactionId())
            .status(transaction.getPaymentStatus())
            .channel(transaction.getGatewayName())
            .errorMessage(transaction.getErrorMessage())
            .build();
    }

    /**
     * 获取下一个ID
     */
    private Long getNextId() {
        return Long.valueOf(IdUtil.getSnowflakeNextIdStr());
    }
}

