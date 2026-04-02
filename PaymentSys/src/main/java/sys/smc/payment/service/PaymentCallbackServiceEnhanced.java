package sys.smc.payment.service;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.dto.PaymentCallbackData;
import sys.smc.payment.entity.PaymentCallbackLog;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.OptimisticLockException;
import sys.smc.payment.gateway.PaymentGateway;
import sys.smc.payment.gateway.PaymentGatewayRouter;
import sys.smc.payment.mapper.PaymentCallbackLogMapper;
import sys.smc.payment.mapper.PaymentTransactionMapper;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 回调处理服务（增强版 - 支持Redis去重）
 * 
 * 改进点：
 * 1. Redis去重：防止同一回调重复处理
 * 2. 终态检查：防止终态交易被修改
 * 3. 乐观锁：防止并发修改
 */
@Service
@Slf4j
public class PaymentCallbackServiceEnhanced {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private PaymentCallbackLogMapper callbackLogMapper;

    @Autowired
    private PaymentGatewayRouter gatewayRouter;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${payment.callback.dedup.expire:86400}")
    private Long callbackDedupExpire; // 回调去重过期时间（秒），默认24小时

    private static final String CALLBACK_PROCESSED_KEY = "payment:callback:processed:";

    /**
     * 异步处理回调（增强版）
     */
    @Async("callbackExecutor")
    @Transactional(rollbackFor = Exception.class,
                   isolation = Isolation.READ_COMMITTED,
                   propagation = Propagation.REQUIRES_NEW)
    public void processCallback(String rawBody, Map<String, String> headers, 
                                 String clientIp, PaymentChannel channel) {
        long startTime = System.currentTimeMillis();
        PaymentCallbackLog callbackLog = new PaymentCallbackLog();
        callbackLog.setId(Long.valueOf(IdUtil.getSnowflakeNextIdStr()));

        try {
            log.info("开始处理 {} 回调，IP：{}", channel.getName(), clientIp);

            // 获取对应渠道的网关
            PaymentGateway gateway = gatewayRouter.getGateway(channel);

            // 1. 验证签名
            String signature = headers.get("X-Signature");
            boolean signatureValid = gateway.verifyCallback(rawBody, signature, headers);
            callbackLog.setSignatureValid(signatureValid ? 1 : 0);
            callbackLog.setSignatureValue(signature);

            if (!signatureValid) {
                log.error("[{}] 回调签名无效", channel.getName());
                callbackLog.setProcessingStatus("FAILED");
                callbackLog.setErrorMessage("签名无效");
                return;
            }

            // 2. 解析回调数据
            PaymentCallbackData callbackData = gateway.parseCallbackData(rawBody);
            callbackLog.setGatewayTransactionId(callbackData.getGatewayTransactionId());
            callbackLog.setGatewayStatus(callbackData.getPaymentStatus());

            // 3. ⭐ Redis去重检查（第一层防护）
            if (redisTemplate != null) {
                if (isDuplicateCallbackByRedis(callbackData)) {
                    log.warn("[{}] Redis去重拦截，网关交易ID：{}", 
                        channel.getName(), callbackData.getGatewayTransactionId());
                    callbackLog.setProcessingStatus("DUPLICATE_REDIS");
                    return;
                }
            }

            // 4. 查找交易
            PaymentTransaction transaction = transactionMapper.selectByGatewayTransactionId(
                callbackData.getGatewayTransactionId()
            );

            if (transaction == null) {
                transaction = transactionMapper.selectByOrderReference(callbackData.getOrderReference());
            }

            if (transaction == null) {
                log.error("[{}] 交易未找到，网关交易ID：{}", 
                    channel.getName(), callbackData.getGatewayTransactionId());
                callbackLog.setProcessingStatus("FAILED");
                callbackLog.setErrorMessage("交易未找到");
                return;
            }

            callbackLog.setTransactionId(transaction.getTransactionId());

            // 5. ⭐ 终态检查（第二层防护）
            if (isTerminalStatus(transaction.getPaymentStatus())) {
                log.warn("[{}] 交易已处于终态，交易ID：{}，状态：{}",
                    channel.getName(), transaction.getTransactionId(), 
                    transaction.getPaymentStatus());
                callbackLog.setProcessingStatus("DUPLICATE_TERMINAL");
                return;
            }

            // 6. ⭐ 使用乐观锁更新（第三层防护）
            boolean updated = updateTransactionStatus(transaction, callbackData);

            if (!updated) {
                log.warn("[{}] 由于版本冲突更新交易失败，交易ID：{}", 
                    channel.getName(), transaction.getTransactionId());
                callbackLog.setProcessingStatus("RETRY");
                throw new OptimisticLockException("版本冲突");
            }

            // 7. 触发下游动作
            if (PaymentStatus.SUCCESS.name().equals(callbackData.getPaymentStatus())) {
                log.info("[{}] 支付成功，触发订单确认，交易ID：{}", 
                    channel.getName(), transaction.getTransactionId());
                notifyOrderSystem(transaction);
            }

            callbackLog.setProcessingStatus("SUCCESS");
            log.info("[{}] 回调处理成功，交易ID：{}", channel.getName(), transaction.getTransactionId());

        } catch (Exception e) {
            log.error("[{}] 处理回调时出错", channel.getName(), e);
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
     * Redis去重检查
     * 
     * @return true=重复，false=首次
     */
    private boolean isDuplicateCallbackByRedis(PaymentCallbackData callbackData) {
        String callbackKey = CALLBACK_PROCESSED_KEY + 
                             callbackData.getGatewayTransactionId() + ":" +
                             callbackData.getPaymentStatus();
        
        // 尝试设置键，如果键已存在则返回false
        Boolean result = redisTemplate.opsForValue().setIfAbsent(
            callbackKey,
            System.currentTimeMillis(),
            callbackDedupExpire,
            TimeUnit.SECONDS
        );
        
        // result为null或false表示键已存在（重复回调）
        return Boolean.FALSE.equals(result);
    }

    /**
     * 通知订单系统
     */
    private void notifyOrderSystem(PaymentTransaction transaction) {
        // TODO: 实现订单系统通知逻辑
        log.info("通知订单系统，订单号：{}，支付状态：{}", 
            transaction.getOrderReference(), transaction.getPaymentStatus());
    }

    /**
     * 更新交易状态（使用乐观锁）
     */
    private boolean updateTransactionStatus(PaymentTransaction transaction, 
                                             PaymentCallbackData callbackData) {
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

        // 如果回调带有网关交易ID，更新它
        if (callbackData.getGatewayTransactionId() != null) {
            update.setGatewayTransactionId(callbackData.getGatewayTransactionId());
        }

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

