package sys.smc.payment.service;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.dto.PaymentCallbackData;
import sys.smc.payment.entity.PaymentCallbackLog;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.OptimisticLockException;
import sys.smc.payment.mapper.PaymentCallbackLogMapper;
import sys.smc.payment.mapper.PaymentTransactionMapper;
import sys.smc.payment.util.SignatureVerifier;

import java.util.Date;
import java.util.Map;

/**
 * 回调处理服务
 */
@Service
@Slf4j
public class PaymentCallbackService {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private PaymentCallbackLogMapper callbackLogMapper;

    @Autowired
    private SignatureVerifier signatureVerifier;

    /**
     * 异步处理回调
     */
    @Async("callbackExecutor")
    @Transactional(rollbackFor = Exception.class,
                   isolation = Isolation.READ_COMMITTED,
                   propagation = Propagation.REQUIRES_NEW)
    public void processCallback(String rawBody, Map<String, String> headers, String clientIp) {
        long startTime = System.currentTimeMillis();
        PaymentCallbackLog callbackLog = new PaymentCallbackLog();
        callbackLog.setId(Long.valueOf(IdUtil.getSnowflakeNextIdStr()));

        try {
            log.info("开始处理回调，IP：{}", clientIp);

            // 1. 解析回调数据
            PaymentCallbackData callbackData = JSON.parseObject(rawBody, PaymentCallbackData.class);
            callbackLog.setGatewayTransactionId(callbackData.getGatewayTransactionId());
            callbackLog.setGatewayStatus(callbackData.getPaymentStatus());

            // 2. 验证签名
            String signature = headers.get("X-Signature");
            boolean signatureValid = signatureVerifier.verify(rawBody, signature);
            callbackLog.setSignatureValid(signatureValid ? 1 : 0);
            callbackLog.setSignatureValue(signature);

            if (!signatureValid) {
                log.error("回调签名无效，银行交易ID：{}", callbackData.getGatewayTransactionId());
                callbackLog.setProcessingStatus("FAILED");
                callbackLog.setErrorMessage("签名无效");
                return;
            }

            // 3. 查找交易
            PaymentTransaction transaction = transactionMapper.selectByGatewayTransactionId(
                callbackData.getGatewayTransactionId()
            );

            if (transaction == null) {
                log.error("交易未找到，银行交易ID：{}", callbackData.getGatewayTransactionId());
                callbackLog.setProcessingStatus("FAILED");
                callbackLog.setErrorMessage("交易未找到");
                return;
            }

            callbackLog.setTransactionId(transaction.getTransactionId());

            // 4. 检查是否已处理（幂等性）
            if (isTerminalStatus(transaction.getPaymentStatus())) {
                log.warn("交易已处于终态，交易ID：{}，状态：{}",
                    transaction.getTransactionId(), transaction.getPaymentStatus());
                callbackLog.setProcessingStatus("DUPLICATE");
                return;
            }

            // 5. 使用乐观锁更新交易状态
            boolean updated = updateTransactionStatus(transaction, callbackData);

            if (!updated) {
                log.warn("由于版本冲突更新交易失败，交易ID：{}", transaction.getTransactionId());
                callbackLog.setProcessingStatus("RETRY");
                throw new OptimisticLockException("版本冲突");
            }

            // 6. 触发下游动作（订单确认、通知等）
            if (PaymentStatus.SUCCESS.name().equals(callbackData.getPaymentStatus())) {
                log.info("支付成功，触发订单确认，交易ID：{}", transaction.getTransactionId());
                // TODO: 通知订单系统
            }

            callbackLog.setProcessingStatus("SUCCESS");
            log.info("回调处理成功，交易ID：{}", transaction.getTransactionId());

        } catch (Exception e) {
            log.error("处理回调时出错", e);
            callbackLog.setProcessingStatus("FAILED");
            callbackLog.setErrorMessage(e.getMessage());
            throw e;

        } finally {
            // 总是记录回调尝试
            long processingTime = System.currentTimeMillis() - startTime;
            callbackLog.setProcessingTimeMs((int) processingTime);
            callbackLog.setRequestBody(rawBody);
            callbackLog.setRequestHeaders(JSON.toJSONString(headers));
            callbackLog.setRequestIp(clientIp);
            callbackLog.setRequestMethod("POST");
            callbackLog.setCallbackTime(new Date());
            callbackLog.setCreateTime(new Date());

            callbackLogMapper.insert(callbackLog);
        }
    }

    /**
     * 更新交易状态（使用乐观锁）
     */
    private boolean updateTransactionStatus(PaymentTransaction transaction, PaymentCallbackData callbackData) {
        PaymentTransaction update = new PaymentTransaction();
        update.setId(transaction.getId());
        update.setVersion(transaction.getVersion()); // 乐观锁的关键
        update.setPreviousStatus(transaction.getPaymentStatus());
        update.setPaymentStatus(callbackData.getPaymentStatus());
        update.setStatusUpdateTime(new Date());
        update.setCallbackReceived(1);
        update.setCallbackCount((transaction.getCallbackCount() == null ? 0 : transaction.getCallbackCount()) + 1);
        update.setLastCallbackTime(new Date());
        update.setUpdateUser("CALLBACK");

        int rows = transactionMapper.updateById(update);
        return rows > 0;
    }

    /**
     * 判断是否为终态
     */
    private boolean isTerminalStatus(String status) {
        return PaymentStatus.SUCCESS.name().equals(status) ||
               PaymentStatus.FAILED.name().equals(status) ||
               PaymentStatus.REFUNDED.name().equals(status);
    }
}

