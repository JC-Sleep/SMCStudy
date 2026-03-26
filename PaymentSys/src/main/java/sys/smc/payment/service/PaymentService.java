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
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.PaymentException;
import sys.smc.payment.gateway.StandardCharteredGatewayClient;
import sys.smc.payment.gateway.dto.GatewayPaymentResponse;
import sys.smc.payment.mapper.PaymentTransactionMapper;

import java.util.Date;

/**
 * 支付服务
 */
@Service
@Slf4j
public class PaymentService {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private StandardCharteredGatewayClient gatewayClient;

    @Value("${payment.timeout.threshold:300}")
    private Integer timeoutThreshold;

    /**
     * 发起支付
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("开始处理支付请求，订单号：{}", request.getOrderReference());

        // 1. 生成幂等键
        String idempotencyKey = generateIdempotencyKey(request);

        // 2. 检查交易是否已存在（幂等性）
        PaymentTransaction existing = transactionMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            log.warn("检测到重复支付请求，幂等键：{}", idempotencyKey);
            return buildResponseFromTransaction(existing);
        }

        // 3. 创建INIT状态的交易记录
        PaymentTransaction transaction = buildTransaction(request, idempotencyKey);
        transaction.setPaymentStatus(PaymentStatus.INIT.name());
        transaction.setId(getNextId());
        transactionMapper.insert(transaction);

        try {
            // 4. 调用渣打银行API
            log.info("调用渣打银行API，交易ID：{}", transaction.getTransactionId());
            GatewayPaymentResponse gatewayResponse = gatewayClient.createPayment(request, transaction.getTransactionId());

            // 5. 更新交易信息
            transaction.setGatewayTransactionId(gatewayResponse.getTransactionId());
            transaction.setGatewayOrderNo(gatewayResponse.getOrderNo());
            transaction.setPaymentStatus(PaymentStatus.PENDING.name());
            transaction.setStatusUpdateTime(new Date());
            transactionMapper.updateById(transaction);

            log.info("支付发起成功，交易ID：{}，银行交易ID：{}",
                transaction.getTransactionId(), gatewayResponse.getTransactionId());

            // 6. 返回响应给客户端
            return PaymentInitResponse.builder()
                .transactionId(transaction.getTransactionId())
                .paymentUrl(gatewayResponse.getPaymentUrl())
                .status(PaymentStatus.PENDING.name())
                .expiryTime(DateUtil.formatDateTime(DateUtil.offsetSecond(new Date(), timeoutThreshold)))
                .build();

        } catch (Exception e) {
            log.error("支付发起失败，订单号：{}", request.getOrderReference(), e);
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
    private PaymentTransaction buildTransaction(PaymentInitRequest request, String idempotencyKey) {
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
        transaction.setGatewayName("STANDARD_CHARTERED");
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

