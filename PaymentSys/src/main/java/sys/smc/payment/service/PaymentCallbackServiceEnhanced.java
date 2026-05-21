package sys.smc.payment.service;

import cn.hutool.core.lang.Snowflake;
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
import sys.smc.payment.exception.IllegalStateTransitionException;
import sys.smc.payment.exception.OptimisticLockException;
import sys.smc.payment.gateway.GatewayHealthMonitor;
import sys.smc.payment.gateway.PaymentGateway;
import sys.smc.payment.gateway.PaymentGatewayRouter;
import sys.smc.payment.mapper.PaymentCallbackLogMapper;
import sys.smc.payment.mapper.PaymentTransactionMapper;
import sys.smc.payment.statemachine.PaymentStateMachine;
import sys.smc.payment.statemachine.TransitionContext;

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

    /** 自研轻量状态机：在 DB 写入前校验状态转换合法性 */
    @Autowired
    private PaymentStateMachine stateMachine;

    /** 渠道健康监控：回调处理成功/失败后更新熔断计数 */
    @Autowired
    private GatewayHealthMonitor healthMonitor;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    // ② 修复：注入 workerId 配置好的 Snowflake bean
    @Autowired
    private Snowflake snowflake;

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
        callbackLog.setId(snowflake.nextId());

        try {
            log.info("开始处理 {} 回调，IP：{}", channel.getName(), clientIp);

            // 获取对应渠道的网关
            PaymentGateway gateway = gatewayRouter.getGateway(channel);

            // 1. 验证签名
            // ⚠️ 不同渠道签名 Header 不同：
            //   CyberSource → v-c-signature（HMAC-SHA256 of raw body）
            //   SCB         → X-Signature
            // 优先取 v-c-signature，降级到 X-Signature，保持向后兼容。
            String signature = headers.getOrDefault("v-c-signature", headers.get("X-Signature"));
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

            // 6. ⭐ 状态机校验 + 乐观锁 DB 更新（第四层防护，核心安全保障）
            //
            // 原子性保证：
            //   - 此处处于 @Transactional(REQUIRES_NEW) 事务中
            //   - stateMachine.transition() 仅做内存校验（不写 DB），校验失败抛异常
            //   - 校验通过后，updateTransactionWithStateMachine 立即在同一事务内以乐观锁写 DB
            //   - 若其他线程在"校验通过"和"updateById"之间修改了 DB，乐观锁（WHERE version=V）
            //     返回 0 行，触发 OptimisticLockException，本事务回滚，调用方重试
            boolean updated = updateTransactionWithStateMachine(transaction, callbackData, signatureValid, channel);

            if (!updated) {
                log.warn("[{}] 乐观锁版本冲突，交易ID：{}", channel.getName(), transaction.getTransactionId());
                callbackLog.setProcessingStatus("RETRY");
                throw new OptimisticLockException("版本冲突，请重试 txn=" + transaction.getTransactionId());
            }

            // 7. 触发下游动作
            if (PaymentStatus.SUCCESS.name().equals(callbackData.getPaymentStatus())) {
                log.info("[{}] 支付成功，触发订单确认，交易ID：{}",
                        channel.getName(), transaction.getTransactionId());
                notifyOrderSystem(transaction);
            }

            // 8. ⭐ 回调处理成功 → 通知健康监控（渠道工作正常）
            healthMonitor.recordSuccess(channel);
            callbackLog.setProcessingStatus("SUCCESS");
            log.info("[{}] 回调处理成功，交易ID：{}", channel.getName(), transaction.getTransactionId());

        } catch (IllegalStateTransitionException e) {
            // 状态机拒绝的非法转换（如 TIMEOUT→SUCCESS 银行迟到回调）
            // 这不是网关宕机问题，不累加熔断计数
            log.error("[{}] 状态机拒绝非法转换: {} txn={}",
                    channel.getName(), e.getMessage(),
                    callbackLog.getTransactionId());
            callbackLog.setProcessingStatus("ILLEGAL_TRANSITION");
            callbackLog.setErrorMessage(e.getMessage());
            // 不 re-throw：返回 HTTP 200 给网关，避免网关无限重试

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
     * 通知订单系统（支付成功后的下游触发）
     *
     * ─── 实现说明 ──────────────────────────────────────────────────────────────
     * 此方法在 @Transactional(REQUIRES_NEW) 事务提交后由调用方异步触发，
     * 不在事务内，因此不会因 HTTP 超时导致事务回滚。
     *
     * 防死账机制的两层保障：
     *   Layer 1（此处）：同步 HTTP 调用订单系统，大多数情况下即时通知
     *   Layer 2（兜底）：OrderSuccessNotificationJob 定期扫描 ORDER_NOTIFIED=0 的 SUCCESS 交易重试
     *
     * ORDER_NOTIFIED 字段已在 updateTransactionWithStateMachine 中设为 0，
     * 本方法通知成功后将其更新为 1；若本方法失败，Job 会在下一轮扫描中重试。
     */
    private void notifyOrderSystem(PaymentTransaction transaction) {
        log.info("[订单通知] 开始通知订单系统 orderRef={} txn={} amount={}",
                transaction.getOrderReference(),
                transaction.getTransactionId(),
                transaction.getAmount());

        try {
            // ── 实际通知逻辑（选择其中之一实现）─────────────────────────────────
            // Option A（推荐-生产）：发送 MQ 消息，订单服务幂等消费
            // mqTemplate.send("payment.success", buildPaymentSuccessEvent(transaction));

            // Option B（过渡方案）：同步 HTTP 调用订单系统
            // RestTemplate restTemplate = ...;
            // restTemplate.postForObject(orderServiceUrl + "/internal/payment/confirmed",
            //     Map.of("orderRef", transaction.getOrderReference(),
            //            "transactionId", transaction.getTransactionId(),
            //            "amount", transaction.getAmount()), Void.class);

            // ── 临时占位：结构化日志（至少能被日志采集平台捕获并告警）──────────
            log.warn("[订单通知-待接MQ] PAYMENT_SUCCESS_EVENT orderRef={} txnId={} amount={} gateway={}",
                    transaction.getOrderReference(),
                    transaction.getTransactionId(),
                    transaction.getAmount(),
                    transaction.getGatewayName());

            // 通知"成功"后，把 ORDER_NOTIFIED 更新为 1（告诉扫描 Job 不再重试）
            PaymentTransaction flag = new PaymentTransaction();
            flag.setId(transaction.getId());
            flag.setOrderNotified(1);
            transactionMapper.updateById(flag);

            log.info("[订单通知] 完成，ORDER_NOTIFIED 已置 1 txn={}", transaction.getTransactionId());

        } catch (Exception e) {
            // 不 re-throw：通知失败不影响支付状态（支付已经 SUCCESS 了）
            // ORDER_NOTIFIED 仍然是 0，OrderSuccessNotificationJob 会兜底重试
            log.error("[订单通知] 失败！ORDER_NOTIFIED 保持 0，等待 Job 兜底重试。txn={} error={}",
                    transaction.getTransactionId(), e.getMessage());
        }
    }

    /**
     * 状态机校验 + 乐观锁 DB 更新（原子操作）
     *
     * ─── 原子性边界 ───────────────────────────────────────────────────────────
     * 调用方 processCallback 已持有 @Transactional(REQUIRES_NEW) 事务。
     * 本方法：
     *   1. 调用 stateMachine.transition() 做内存白名单校验（不写 DB）
     *   2. 立即在同一事务内 transactionMapper.updateById(update)（带 @Version 乐观锁）
     *
     * 如果另一个线程在步骤 1 和步骤 2 之间修改了同一行：
     *   → updateById WHERE version=V 返回 0 行 → 返回 false → 调用方抛 OptimisticLockException
     *   → 本事务回滚 → 无脏数据
     * ─────────────────────────────────────────────────────────────────────────
     *
     * @param signatureValid 签名是否有效（传给状态机 guard：PENDING→SUCCESS 需要签名有效）
     * @return true=更新成功，false=乐观锁冲突（需重试）
     * @throws IllegalStateTransitionException 状态转换不在白名单中（如 TIMEOUT→SUCCESS）
     */
    private boolean updateTransactionWithStateMachine(PaymentTransaction transaction,
                                                      PaymentCallbackData callbackData,
                                                      boolean signatureValid,
                                                      PaymentChannel channel) {
        // 确定目标状态
        PaymentStatus currentStatus = PaymentStatus.valueOf(transaction.getPaymentStatus());
        PaymentStatus targetStatus = resolveTargetStatus(callbackData.getPaymentStatus());

        // 构建转换上下文
        TransitionContext ctx = TransitionContext.builder()
                .transaction(transaction)
                .operator("CALLBACK_" + channel.getCode())
                .remark("银行回调：" + callbackData.getPaymentStatus())
                .signatureValid(signatureValid)
                .build();

        // 状态机白名单校验：非法转换抛 IllegalStateTransitionException（如 TIMEOUT→SUCCESS）
        // 此方法在 @Transactional 事务中，校验失败时事务回滚，DB 不会被修改
        stateMachine.transition(currentStatus, targetStatus, ctx);

        // 校验通过，构建更新对象（必须带 version 字段，触发 MyBatis-Plus 乐观锁 WHERE version=V）
        PaymentTransaction update = new PaymentTransaction();
        update.setId(transaction.getId());
        update.setVersion(transaction.getVersion());           // ← 乐观锁的关键
        update.setPreviousStatus(transaction.getPaymentStatus());
        update.setPaymentStatus(targetStatus.name());
        update.setStatusUpdateTime(new Date());
        update.setCallbackReceived(1);
        update.setCallbackCount((transaction.getCallbackCount() == null ? 0 : transaction.getCallbackCount()) + 1);
        update.setLastCallbackTime(new Date());
        update.setUpdateUser("CALLBACK_" + channel.getCode());
        if (callbackData.getGatewayTransactionId() != null) {
            update.setGatewayTransactionId(callbackData.getGatewayTransactionId());
        }

        int rows = transactionMapper.updateById(update);
        // rows == 0 表示乐观锁冲突（其他线程已修改），返回 false 由调用方抛异常触发事务回滚
        return rows > 0;
    }

    /**
     * 将银行回调状态字符串映射为内部 PaymentStatus 枚举
     * 可扩展：根据网关返回值添加更多映射
     */
    private PaymentStatus resolveTargetStatus(String gatewayPaymentStatus) {
        if ("SUCCESS".equalsIgnoreCase(gatewayPaymentStatus)) return PaymentStatus.SUCCESS;
        if ("FAILED".equalsIgnoreCase(gatewayPaymentStatus))  return PaymentStatus.FAILED;
        // 其他网关状态统一映射为 FAILED，防止产生未知状态
        log.warn("[状态映射] 未知的网关状态: {}，映射为 FAILED", gatewayPaymentStatus);
        return PaymentStatus.FAILED;
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

