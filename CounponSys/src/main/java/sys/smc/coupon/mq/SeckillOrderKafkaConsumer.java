package sys.smc.coupon.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.coupon.config.KafkaConfig;
import sys.smc.coupon.entity.Coupon;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.entity.SeckillOrder;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.mapper.SeckillOrderMapper;
import sys.smc.coupon.service.CouponService;
import sys.smc.coupon.service.PointsService;
import sys.smc.coupon.util.RedisKeys;

/**
 * 秒杀订单Kafka消费者
 * 
 * 高并发场景使用Kafka替代ActiveMQ
 * 支持10W+ QPS
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

    /**
     * 消费秒杀订单消息
     * 
     * concurrency=10: 10个消费者线程并行处理
     * containerFactory: 使用手动确认模式
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_SECKILL_ORDER,
            groupId = "seckill-order-consumer",
            concurrency = "10"
    )
    @Transactional(rollbackFor = Exception.class)
    public void processSeckillOrder(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String orderIdStr = record.value();
        Long orderId = Long.parseLong(orderIdStr);
        
        log.info("处理秒杀订单: orderId={}, partition={}, offset={}", 
                orderId, record.partition(), record.offset());

        SeckillOrder order = null;
        try {
            // 1. 查询订单
            order = orderMapper.selectById(orderId);
            if (order == null || order.getStatus() != 0) {
                log.warn("订单不存在或状态异常: orderId={}", orderId);
                ack.acknowledge(); // 确认消息，不重试
                return;
            }

            // 2. 扣减积分(如果需要)
            if (order.getPointsUsed() != null && order.getPointsUsed() > 0) {
                boolean deducted = pointsService.deductPoints(
                        order.getUserId(),
                        order.getPointsChannel(),
                        order.getPointsUsed(),
                        "秒杀抢券-" + order.getOrderNo()
                );
                if (!deducted) {
                    handleOrderFail(order, "积分扣减失败");
                    ack.acknowledge();
                    return;
                }
            }

            // 3. 获取活动信息
            SeckillActivity activity = activityMapper.selectById(order.getActivityId());
            if (activity == null) {
                handleOrderFail(order, "活动不存在");
                rollbackPointsIfNeeded(order);
                ack.acknowledge();
                return;
            }

            // 4. 发放优惠券
            Coupon coupon = couponService.grantCoupon(
                    order.getUserId(),
                    order.getUserMobile(),
                    activity.getTemplateId(),
                    GrantType.SECKILL.getCode(),
                    "SECKILL-" + order.getActivityId()
            );

            // 5. 同步扣减数据库库存
            int updated = activityMapper.deductStock(order.getActivityId(), 1);
            if (updated == 0) {
                log.warn("数据库库存扣减失败，但Redis已扣减，忽略: activityId={}", order.getActivityId());
            }

            // 6. 更新订单状态为成功
            order.setCouponId(coupon.getId());
            order.setStatus(1); // 成功
            orderMapper.updateById(order);

            log.info("秒杀订单处理成功: orderId={}, couponCode={}", orderId, coupon.getCouponCode());

            // 7. 手动确认消息
            ack.acknowledge();

        } catch (Exception e) {
            log.error("秒杀订单处理失败: orderId={}", orderId, e);
            
            if (order != null) {
                // 回滚Redis库存
                rollbackRedisStock(order.getActivityId());
                // 回滚积分
                rollbackPointsIfNeeded(order);
                // 标记订单失败
                handleOrderFail(order, e.getMessage());
            }
            
            // 确认消息，不重试(已做补偿)
            ack.acknowledge();
        }
    }

    /**
     * 回滚Redis库存
     */
    private void rollbackRedisStock(Long activityId) {
        try {
            String stockKey = RedisKeys.getSeckillStockKey(activityId);
            redisTemplate.opsForValue().increment(stockKey);
            log.info("Redis库存回滚成功: activityId={}", activityId);
        } catch (Exception e) {
            log.error("Redis库存回滚失败: activityId={}", activityId, e);
        }
    }

    /**
     * 回滚积分
     */
    private void rollbackPointsIfNeeded(SeckillOrder order) {
        if (order.getPointsUsed() != null && order.getPointsUsed() > 0) {
            try {
                pointsService.rollbackPoints(
                        order.getUserId(),
                        order.getPointsChannel(),
                        order.getPointsUsed(),
                        "秒杀失败回滚-" + order.getOrderNo()
                );
                log.info("积分回滚成功: userId={}, points={}", order.getUserId(), order.getPointsUsed());
            } catch (Exception e) {
                log.error("积分回滚失败: userId={}, points={}", order.getUserId(), order.getPointsUsed(), e);
            }
        }
    }

    /**
     * 处理订单失败
     */
    private void handleOrderFail(SeckillOrder order, String reason) {
        order.setStatus(2); // 失败
        order.setFailReason(reason);
        orderMapper.updateById(order);
        log.warn("秒杀订单失败: orderId={}, reason={}", order.getId(), reason);
    }
}

