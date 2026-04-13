package sys.smc.payment.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.TimeUnit;

/**
 * 支付服务（增强版 - 支持分布式幂等性）
 * 
 * 改进点：
 * 1. 添加Redis分布式锁
 * 2. 优化幂等键生成策略
 * 3. 支持前端传递幂等Token
 */
@Service
@Slf4j
public class PaymentServiceEnhanced {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private PaymentGatewayRouter gatewayRouter;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 编程式事务模板（Bug2修复用）
     * 用于在Redis锁内部精确控制事务提交时机，确保事务在锁释放前提交
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${payment.timeout.threshold:300}")
    private Integer timeoutThreshold;

    @Value("${payment.idempotency.lock.wait-time:5}")
    private Long lockWaitTime;

    @Value("${payment.idempotency.lock.lease-time:30}")
    private Long lockLeaseTime;

    /**
     * 发起支付（增强版 - 支持分布式幂等性）
     *
     * ╔══════════════════════════════════════════════════════════════════╗
     * ║  ⚠️  BUG-2 修复记录（已修复）                                    ║
     * ║  原代码在这里有 @Transactional，导致事务范围包住了 Redis 锁        ║
     * ║                                                                  ║
     * ║  触发条件：高并发 + Redis可用 + paymentMethod不为null             ║
     * ║                                                                  ║
     * ║  问题时序：                                                       ║
     * ║   T1  线程A：@Transactional 开启事务                             ║
     * ║   T2  线程A：获取 Redis 锁成功                                   ║
     * ║   T3  线程A：双重检查 SELECT → 不存在 ✅                         ║
     * ║   T4  线程A：INSERT 交易记录（事务未提交！）                       ║
     * ║   T5  线程A：finally → lock.unlock()  ← 锁释放了                 ║
     * ║   T6  线程B：获取 Redis 锁成功（A刚释放）                         ║
     * ║   T7  线程B：双重检查 SELECT → READ COMMITTED 看不到A的未提交数据 ║
     * ║             → 查到"不存在"，通过检查！                            ║
     * ║   T8  线程B：INSERT 同一笔交易 ← 重复创建！                       ║
     * ║   T9  线程A：initiatePayment() return → 事务才提交               ║
     * ║                                                                  ║
     * ║  后果：同一用户同一订单被创建两条交易记录，可能双重扣款            ║
     * ║                                                                  ║
     * ║  修复方案：                                                       ║
     * ║   ① 去掉外层 @Transactional（本方法不再开事务）                   ║
     * ║   ② 在 doPaymentWithLock() 内部使用 TransactionTemplate          ║
     * ║      让事务在锁释放之前提交（锁仍持有时 → 事务提交 → 释放锁）      ║
     * ║   ③ 数据库加 UNIQUE INDEX(IDEMPOTENCY_KEY) 作为最后兜底           ║
     * ╚══════════════════════════════════════════════════════════════════╝
     */
    // ✅ Bug2已修复：移除 @Transactional，事务由内部 TransactionTemplate 管理
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        PaymentGateway gateway = gatewayRouter.selectGateway(request.getPaymentMethod());
        return doInitiatePaymentWithDistributedLock(request, gateway);
    }

    /**
     * 发起支付（指定渠道）
     * ✅ Bug2已修复：移除 @Transactional
     */
    public PaymentInitResponse initiatePayment(PaymentInitRequest request, PaymentChannel channel) {
        PaymentGateway gateway = gatewayRouter.getGateway(channel);
        return doInitiatePaymentWithDistributedLock(request, gateway);
    }

    /**
     * 执行支付发起（带分布式锁）
     */
    private PaymentInitResponse doInitiatePaymentWithDistributedLock(
            PaymentInitRequest request, PaymentGateway gateway) {
        
        // 1. 生成幂等键（优化后的策略）
        String idempotencyKey = generateIdempotencyKey(request);
        
        // 2. 如果配置了Redis，使用分布式锁
        if (redissonClient != null) {
            return doPaymentWithLock(request, gateway, idempotencyKey);
        } else {
            // 兜底：使用数据库约束保证幂等性
            log.warn("未配置Redis，使用数据库约束保证幂等性（仅适用于单机部署）");
            return doPaymentWithoutLock(request, gateway, idempotencyKey);
        }
    }

    /**
     * 使用分布式锁的支付流程
     *
     * ╔══════════════════════════════════════════════════════════════════╗
     * ║  Bug2 修复关键点：事务必须在锁释放之前提交                         ║
     * ║                                                                  ║
     * ║  正确顺序（修复后）：                                             ║
     * ║   T1  获取 Redis 锁                                              ║
     * ║   T2  TransactionTemplate.execute() → 开启事务                   ║
     * ║   T3  双重检查 SELECT                                            ║
     * ║   T4  INSERT 交易记录                                            ║
     * ║   T5  execute() 结束 → 事务提交 ← 锁还没释放！                   ║
     * ║   T6  finally → lock.unlock()  ← 事务已提交后才释放锁             ║
     * ║                                                                  ║
     * ║  此时其他线程拿到锁后做双重检查，一定能看到已提交的数据，幂等成功   ║
     * ╚══════════════════════════════════════════════════════════════════╝
     */
    private PaymentInitResponse doPaymentWithLock(
            PaymentInitRequest request,
            PaymentGateway gateway,
            String idempotencyKey) {

        String lockKey = "payment:lock:" + idempotencyKey;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁，最多等待5秒，锁定30秒
            boolean locked = lock.tryLock(lockWaitTime, lockLeaseTime, TimeUnit.SECONDS);

            if (!locked) {
                log.warn("获取分布式锁超时，幂等键：{}", idempotencyKey);
                throw new PaymentException("系统繁忙，请稍后重试");
            }

            log.debug("获取分布式锁成功，幂等键：{}", idempotencyKey);

            // ✅ Bug2修复：用 TransactionTemplate 让事务在 finally 锁释放之前提交
            // execute() 方法返回时事务已提交，而 finally 的 unlock() 还未执行
            return transactionTemplate.execute(txStatus -> {
                // 双重检查：在事务内再次查询（此时能看到所有已提交数据）
                PaymentTransaction existing = transactionMapper.selectByIdempotencyKey(idempotencyKey);
                if (existing != null) {
                    log.info("双重检查发现交易已存在，幂等键：{}", idempotencyKey);
                    return buildResponseFromTransaction(existing);
                }
                // 执行支付创建（INSERT + gateway调用 + UPDATE，全在同一事务内）
                return doInitiatePayment(request, gateway, idempotencyKey);
            }); // ← 事务在这里提交，此时锁依然持有

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取分布式锁被中断", e);
            throw new PaymentException("系统繁忙，请稍后重试", e);
        } finally {
            // ✅ 事务已在 transactionTemplate.execute() 结束时提交
            //    现在才释放锁，其他线程进来双重检查必定能查到已提交的记录
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放分布式锁（事务已提交），幂等键：{}", idempotencyKey);
            }
        }
    }

    /**
     * 不使用分布式锁的支付流程（单机模式）
     */
    private PaymentInitResponse doPaymentWithoutLock(
            PaymentInitRequest request,
            PaymentGateway gateway,
            String idempotencyKey) {
        
        // 检查交易是否已存在
        PaymentTransaction existing = transactionMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            log.warn("检测到重复支付请求，幂等键：{}", idempotencyKey);
            return buildResponseFromTransaction(existing);
        }
        
        return doInitiatePayment(request, gateway, idempotencyKey);
    }

    /**
     * 实际的支付创建逻辑
     */
    private PaymentInitResponse doInitiatePayment(
            PaymentInitRequest request,
            PaymentGateway gateway,
            String idempotencyKey) {
        
        log.info("开始处理支付请求，订单号：{}，渠道：{}", 
            request.getOrderReference(), gateway.getChannelName());

        // 创建INIT状态的交易记录
        PaymentTransaction transaction = buildTransaction(request, idempotencyKey, gateway.getChannel());
        transaction.setPaymentStatus(PaymentStatus.INIT.name());
        transaction.setId(getNextId());
        transactionMapper.insert(transaction);

        try {
            // 调用网关API
            log.info("调用 {} API，交易ID：{}", gateway.getChannelName(), transaction.getTransactionId());
            GatewayPaymentResponse gatewayResponse = gateway.createPayment(request, transaction.getTransactionId());

            // 更新交易信息
            transaction.setGatewayTransactionId(gatewayResponse.getTransactionId());
            transaction.setGatewayOrderNo(gatewayResponse.getOrderNo());
            transaction.setPaymentStatus(PaymentStatus.PENDING.name());
            transaction.setStatusUpdateTime(new Date());
            transactionMapper.updateById(transaction);

            log.info("支付发起成功，交易ID：{}，网关交易ID：{}",
                transaction.getTransactionId(), gatewayResponse.getTransactionId());

            // 返回响应给客户端
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
     * 生成幂等键（优化策略）
     *
     * 优先级：
     *   策略1：前端传递幂等Token（UUID，强烈推荐）
     *   策略2：订单号 + 支付方式（前端无Token时的兜底）
     *   策略3：❌ 已废弃（原来用时间戳，有严重Bug）
     *
     * ╔══════════════════════════════════════════════════════════════════╗
     * ║  ⚠️  BUG-1 记录（已修复）：原策略3 使用时间戳导致幂等失效          ║
     * ║                                                                  ║
     * ║  原代码：                                                        ║
     * ║   String key = orderRef + "_" + amount + "_" +                  ║
     * ║                System.currentTimeMillis(); ← 每次都不同！        ║
     * ║                                                                  ║
     * ║  触发条件：                                                       ║
     * ║   前端未传 idempotencyToken 且 request.paymentMethod == null     ║
     * ║   （部分旧版前端未传 paymentMethod 字段时会走到策略3）             ║
     * ║                                                                  ║
     * ║  问题时序：                                                       ║
     * ║   T1  用户点击支付，请求1到达 → key = ORDER001_100_1710000001000 ║
     * ║       → INSERT 交易A（PENDING），返回支付URL                     ║
     * ║   T2  网络超时，用户看不到响应，前端重试                           ║
     * ║   T3  请求2到达 → key = ORDER001_100_1710000001500 ← 时间变了！  ║
     * ║       → 幂等检查：这个key没见过 → 通过！                          ║
     * ║       → INSERT 交易B（PENDING），又返回一个支付URL                ║
     * ║   T4  用户拿到两个支付URL，支付了两次                             ║
     * ║   T5  银行扣款2次，用户损失 100 × 2 = 200 元 !!                  ║
     * ║                                                                  ║
     * ║  修复方案：策略3改为抛异常，强制调用方提供稳定的幂等键             ║
     * ║  根治方案：前端统一生成 UUID 作为 idempotencyToken                ║
     * ╚══════════════════════════════════════════════════════════════════╝
     */
    private String generateIdempotencyKey(PaymentInitRequest request) {

        // ✅ 策略1：前端传递幂等Token（UUID，最推荐，前端在点击支付时生成并缓存）
        if (request.getIdempotencyToken() != null && !request.getIdempotencyToken().isEmpty()) {
            log.debug("使用前端幂等Token：{}", request.getIdempotencyToken());
            return request.getIdempotencyToken();
        }

        // ✅ 策略2：订单号 + 支付方式（适合一订单支持多种支付方式，语义稳定）
        if (request.getPaymentMethod() != null && !request.getPaymentMethod().isEmpty()) {
            String key = request.getOrderReference() + "_" + request.getPaymentMethod();
            log.debug("使用订单号+支付方式作为幂等键：{}", key);
            return key;
        }

        // ❌ 策略3（已废弃）：原来用时间戳，每次请求key不同，幂等失效，可导致重复扣款
        // 旧代码：System.currentTimeMillis() ← 绝对不能用，已删除
        // 修复：强制抛异常，让调用方修正请求（必须提供 idempotencyToken 或 paymentMethod）
        log.error("【Bug1修复】收到不完整请求：订单号={}，idempotencyToken=null，paymentMethod=null。" +
                  "拒绝处理以防重复扣款。请前端传递 idempotencyToken (UUID) 字段。",
                  request.getOrderReference());
        throw new PaymentException(
            "请求缺少幂等标识：请传递 idempotencyToken（推荐）或 paymentMethod 字段，" +
            "以防止网络重试导致重复扣款"
        );
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
     * 退款（防重复退款版本）
     *
     * 核心防重原理（三步走）：
     *  Step1: 用乐观锁原子性地把状态从 SUCCESS → REFUNDING
     *         只有第一个线程能成功（影响行数=1），后续线程拿到0行直接报错
     *  Step2: 凭独占权调用银行网关
     *  Step3: 根据网关结果更新为 REFUNDED 或 REFUND_FAILED
     *
     * 不再出现两个线程都通过"状态=SUCCESS"检查后双重调用网关的问题
     */
    @Transactional(rollbackFor = Exception.class)
    public GatewayRefundResponse refund(RefundRequest request) {

        // ── Step 0：查询原交易 ────────────────────────────────────────────
        PaymentTransaction transaction = transactionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentTransaction>()
                .eq(PaymentTransaction::getTransactionId, request.getTransactionId())
        );

        if (transaction == null) {
            throw new PaymentException("原交易不存在");
        }

        // 退款中/已退款/失败等非SUCCESS状态，直接拒绝
        if (!PaymentStatus.SUCCESS.name().equals(transaction.getPaymentStatus())) {
            throw new PaymentException("当前状态不允许退款，状态：" + transaction.getPaymentStatus());
        }

        // ── Step 1：原子抢占退款权（SUCCESS → REFUNDING），乐观锁保护 ─────
        // MyBatis-Plus 翻译为：
        //   UPDATE PAYMENT_TRANSACTION
        //     SET STATUS='REFUNDING', VERSION=VERSION+1, UPDATE_TIME=now()
        //   WHERE ID=? AND VERSION=?     ← version不匹配→0行→并发退款被拦截
        PaymentTransaction claimUpdate = new PaymentTransaction();
        claimUpdate.setId(transaction.getId());
        claimUpdate.setVersion(transaction.getVersion());          // 乐观锁关键字段
        claimUpdate.setPreviousStatus(transaction.getPaymentStatus());
        claimUpdate.setPaymentStatus(PaymentStatus.REFUNDING.name());
        claimUpdate.setStatusUpdateTime(new Date());
        claimUpdate.setUpdateUser(request.getOperator() != null ? request.getOperator() : "SYSTEM");

        int claimed = transactionMapper.updateById(claimUpdate);
        if (claimed == 0) {
            // 另一个线程已经抢先把状态改掉（可能是并发退款，也可能是被其他操作修改）
            log.warn("退款抢占失败（版本冲突），交易ID：{}，当前VERSION：{}",
                transaction.getTransactionId(), transaction.getVersion());
            throw new PaymentException("退款正在处理中，请勿重复提交");
        }

        log.info("退款权抢占成功，交易ID：{}，状态已变更为REFUNDING", transaction.getTransactionId());

        // ── Step 2：调用银行网关（此刻状态=REFUNDING，其他线程已不可进入）──
        PaymentChannel channel = PaymentChannel.fromCode(transaction.getGatewayName());
        PaymentGateway gateway = gatewayRouter.getGateway(channel);

        request.setGatewayTransactionId(transaction.getGatewayTransactionId());
        request.setRefundNo("RF" + IdUtil.getSnowflakeNextIdStr());

        try {
            GatewayRefundResponse response = gateway.refund(request);

            // ── Step 3a：网关成功 → REFUNDED ──────────────────────────────
            PaymentTransaction finalUpdate = new PaymentTransaction();
            finalUpdate.setId(transaction.getId());
            // 不设置version → MyBatis-Plus不加version条件 → 直接更新（此时已是REFUNDING的独占状态）
            if (response.isSuccess()) {
                finalUpdate.setPaymentStatus(PaymentStatus.REFUNDED.name());
                log.info("退款成功，交易ID：{}，退款单号：{}", transaction.getTransactionId(), request.getRefundNo());
            } else {
                // ── Step 3b：网关业务失败（如余额不足等）→ REFUND_FAILED ────
                finalUpdate.setPaymentStatus(PaymentStatus.REFUND_FAILED.name());
                finalUpdate.setErrorMessage("网关退款失败：" + response.getErrorMessage());
                log.error("退款网关返回失败，交易ID：{}，原因：{}", transaction.getTransactionId(), response.getErrorMessage());
            }
            finalUpdate.setStatusUpdateTime(new Date());
            finalUpdate.setUpdateUser(request.getOperator() != null ? request.getOperator() : "SYSTEM");
            transactionMapper.updateById(finalUpdate);

            return response;

        } catch (Exception e) {
            // ── Step 3c：网关调用异常（超时/网络）→ REFUND_FAILED，需人工干预 ──
            log.error("退款网关调用异常，交易ID：{}，需人工核查", transaction.getTransactionId(), e);
            PaymentTransaction failUpdate = new PaymentTransaction();
            failUpdate.setId(transaction.getId());
            failUpdate.setPaymentStatus(PaymentStatus.REFUND_FAILED.name());
            failUpdate.setErrorMessage("网关异常：" + e.getMessage() + "，请人工核查");
            failUpdate.setStatusUpdateTime(new Date());
            failUpdate.setUpdateUser("SYSTEM");
            transactionMapper.updateById(failUpdate);
            throw new PaymentException("退款网关异常，已标记为REFUND_FAILED，请联系运营处理", e);
        }
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
        transaction.setGatewayName(channel.getCode());
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

