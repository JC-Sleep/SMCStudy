package sys.smc.coupon.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.coupon.config.KafkaConfig;
import sys.smc.coupon.entity.Coupon;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.entity.SeckillOrder;
import sys.smc.coupon.entity.SeckillRetryTask;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.mapper.SeckillOrderMapper;
import sys.smc.coupon.mapper.SeckillRetryTaskMapper;
import sys.smc.coupon.service.PointsService;
import sys.smc.coupon.util.RedisKeys;

import java.time.LocalDateTime;

/**
 * 秒杀订单 Kafka 消费者（2026-04-22 重构：新增重试+死信机制）
 *
 * ══════════════════════════════════════════════════════════════
 * 修复前（旧逻辑）：
 *   消费失败 → 直接标记订单失败 → 用户权益永久丢失
 *   临时故障（DB抖动1秒、积分服务短暂超时）也会导致订单失败
 *
 * 修复后（新逻辑）：
 *   消费失败
 *     ├─ 业务拒绝（活动不存在、积分余额不足）→ 直接失败（重试也没用）
 *     └─ 临时异常（网络抖动、服务短暂超时）
 *          → 回滚Redis库存
 *          → 写 T_SECKILL_RETRY_TASK（不改订单状态，保持status=0）
 *          → Job每30s扫描 → 指数退避重试（1min→5min→30min）
 *               ├─ 重试成功 → 订单status=1，用户收到券 ✅
 *               └─ 3次仍失败 → 订单status=2 + 发DLT + 人工告警
 *
 * 与 T_GRANT_TASK 模式一致（兑换码发券补偿），系统内统一方案
 * ══════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderKafkaConsumer {

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final CouponService couponService;
    private final PointsService pointsService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SeckillRetryTaskMapper retryTaskMapper;

    /**
     * 消费秒杀订单消息（主消费者）
     * concurrency=10: 10个消费者线程并行处理
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_SECKILL_ORDER,
            groupId = "seckill-order-consumer",
            concurrency = "10"
    )
    @Transactional(rollbackFor = Exception.class)
    public void processSeckillOrder(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Long orderId = Long.parseLong(record.value());
        log.info("[秒杀消费] orderId={} partition={} offset={}",
                orderId, record.partition(), record.offset());

        SeckillOrder order = null;
        try {
            order = orderMapper.selectById(orderId);

            // ── 幂等检查：已处理过的直接跳过 ──
            if (order == null) {
                log.warn("[秒杀消费] 订单不存在，跳过 orderId={}", orderId);
                ack.acknowledge();
                return;
            }
            if (order.getStatus() != 0) {
                log.info("[秒杀消费] 订单已处理(status={})，幂等跳过 orderId={}", order.getStatus(), orderId);
                ack.acknowledge();
                return;
            }

            // ── 执行核心业务逻辑 ──
            doProcessOrder(order);
            ack.acknowledge();
            log.info("[秒杀消费] 处理成功 orderId={}", orderId);

        } catch (BusinessRejectException e) {
            // ── 业务拒绝：重试也没用，直接标记失败 ──
            log.warn("[秒杀消费] 业务拒绝，直接失败 orderId={} reason={}", orderId, e.getMessage());
            if (order != null) {
                rollbackRedisStock(order.getActivityId());
                rollbackPointsIfNeeded(order);
                markOrderFailed(order, e.getMessage());
            }
            ack.acknowledge();

        } catch (Exception e) {
            // ── 临时异常：写重试任务，不直接标记失败，保护用户权益 ──
            log.error("[秒杀消费] 临时异常，写重试任务，不直接失败 orderId={}", orderId, e);
            if (order != null) {
                rollbackRedisStock(order.getActivityId());  // 让其他用户可继续抢
                rollbackPointsIfNeeded(order);              // 回滚已扣积分（如已扣）
                writeRetryTask(order, e.getMessage());      // 写重试任务（order保持status=0）
            }
            ack.acknowledge();
        }
    }

    /**
     * 核心业务逻辑（被主消费者 和 重试Job 共用）
     * 调用前必须保证 order.status == 0（幂等）
     */
    public void doProcessOrder(SeckillOrder order) throws Exception {
        // 1. 扣减积分（如需要）
        if (order.getPointsUsed() != null && order.getPointsUsed() > 0) {
            boolean deducted = pointsService.deductPoints(
                    order.getUserId(),
                    order.getPointsChannel(),
                    order.getPointsUsed(),
                    "秒杀抢券-" + order.getOrderNo()
            );
            if (!deducted) {
                // 积分扣减被业务拒绝（余额不足等）→ 不可重试
                throw new BusinessRejectException("积分扣减失败（余额不足或账户异常）");
            }
        }

        // 2. 获取活动信息
        SeckillActivity activity = activityMapper.selectById(order.getActivityId());
        if (activity == null) {
            throw new BusinessRejectException("活动不存在 activityId=" + order.getActivityId());
        }

        // 3. 发放优惠券
        Coupon coupon = couponService.grantCoupon(
                order.getUserId(),
                order.getUserMobile(),
                activity.getTemplateId(),
                GrantType.SECKILL.getCode(),
                "SECKILL-" + order.getActivityId()
        );

        // 4. 扣减 DB 库存
        int updated = activityMapper.deductStock(order.getActivityId(), 1);
        if (updated == 0) {
            log.warn("[秒杀消费] DB库存扣减失败（Redis已扣减），忽略 activityId={}", order.getActivityId());
        }

        // 5. 更新订单为成功
        order.setCouponId(coupon.getId());
        order.setStatus(1);
        orderMapper.updateById(order);
        log.info("[秒杀消费] 订单处理完成 orderId={} couponCode={}", order.getId(), coupon.getCouponCode());
    }

    // ══════════════════ 私有辅助方法 ══════════════════

    /**
     * 写重试任务到 DB（order保持status=0，等待Job重试）
     * 极端情况下（DB不可用），降级为直接标记失败
     */
    private void writeRetryTask(SeckillOrder order, String failReason) {
        try {
            SeckillRetryTask task = new SeckillRetryTask();
            task.setId(IdWorker.getId());
            task.setOrderId(order.getId());
            task.setActivityId(order.getActivityId());
            task.setUserId(order.getUserId());
            task.setStatus(0);
            task.setRetryCount(0);
            task.setMaxRetry(SeckillRetryTask.MAX_RETRY_COUNT);
            task.setFailReason(failReason);
            // 1分钟后首次重试
            task.setNextRetryTime(
                    LocalDateTime.now().plusMinutes(SeckillRetryTask.RETRY_DELAYS_MINUTES[0]));
            retryTaskMapper.insert(task);
            log.info("[重试任务] 已创建 taskId={} orderId={} 首次重试时间={}",
                    task.getId(), order.getId(), task.getNextRetryTime());
        } catch (Exception ex) {
            // 写重试任务也失败（极端：DB完全不可用），降级直接标记订单失败
            log.error("[重试任务] 写入失败，降级直接标记订单失败 orderId={}", order.getId(), ex);
            markOrderFailed(order, "重试任务写入失败，降级: " + failReason);
        }
    }

    private void rollbackRedisStock(Long activityId) {
        try {
            redisTemplate.opsForValue().increment(RedisKeys.getSeckillStockKey(activityId));
            log.info("[库存回滚] activityId={}", activityId);
        } catch (Exception e) {
            log.error("[库存回滚] 失败 activityId={}", activityId, e);
        }
    }

    private void rollbackPointsIfNeeded(SeckillOrder order) {
        if (order.getPointsUsed() != null && order.getPointsUsed() > 0) {
            try {
                pointsService.rollbackPoints(
                        order.getUserId(), order.getPointsChannel(),
                        order.getPointsUsed(), "秒杀失败回滚-" + order.getOrderNo());
                log.info("[积分回滚] userId={} points={}", order.getUserId(), order.getPointsUsed());
            } catch (Exception e) {
                log.error("[积分回滚] 失败 userId={}", order.getUserId(), e);
            }
        }
    }

    public void markOrderFailed(SeckillOrder order, String reason) {
        order.setStatus(2);
        order.setFailReason(reason);
        orderMapper.updateById(order);
        log.warn("[订单失败] orderId={} reason={}", order.getId(), reason);
    }

    /**
     * 业务拒绝异常（不可重试）
     * 例如：积分余额不足、活动不存在
     * 区别于普通 Exception（临时异常，可以重试）
     */
    public static class BusinessRejectException extends RuntimeException {
        public BusinessRejectException(String message) { super(message); }
    }
}

