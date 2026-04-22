package sys.smc.coupon.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import sys.smc.coupon.config.KafkaConfig;
import sys.smc.coupon.entity.SeckillOrder;
import sys.smc.coupon.mapper.SeckillOrderMapper;

/**
 * 秒杀订单死信消费者（2026-04-22 新增）
 *
 * 触发条件：
 *   SeckillOrderKafkaConsumer 消费失败 → 写 T_SECKILL_RETRY_TASK
 *   → Job 重试3次 仍失败
 *   → forceFailAndSendToDlt() 发消息到此 Topic
 *
 * 职责：
 *   1. 确保订单被标记为失败（status=2）
 *   2. 打印完整的人工处理告警日志（运营可通过日志系统搜索 [死信]）
 *   3. 为运营提供补偿建议（补发券 或 退还积分）
 *   4. TODO：对接钉钉/Slack/邮件告警，实现自动通知
 *
 * 死信消息必须 ack，不能再回队列（否则无限循环）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderDltConsumer {

    private final SeckillOrderMapper orderMapper;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_SECKILL_ORDER_DLT,
            groupId = "seckill-dlt-consumer"
    )
    public void handleDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Long orderId = Long.parseLong(record.value());

        log.error("╔══════════════════════════════════════════════════╗");
        log.error("║ [死信消费者🚨] 收到死信消息，需要人工处理！        ║");
        log.error("╚══════════════════════════════════════════════════╝");
        log.error("[死信] orderId={} partition={} offset={}",
                orderId, record.partition(), record.offset());

        try {
            SeckillOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                log.error("[死信] 订单不存在 orderId={}，可能已被清理", orderId);
                ack.acknowledge();
                return;
            }

            // 确保订单标记为失败（幂等，可能 RetryJob 已经改了）
            if (order.getStatus() != 2) {
                order.setStatus(2);
                order.setFailReason("Kafka消费重试3次失败，进入死信队列，需人工补偿");
                orderMapper.updateById(order);
            }

            // 打印完整信息供运营排查
            log.error("[死信订单详情]");
            log.error("  orderId    = {}", order.getId());
            log.error("  userId     = {}", order.getUserId());
            log.error("  activityId = {}", order.getActivityId());
            log.error("  pointsUsed = {}", order.getPointsUsed());
            log.error("  failReason = {}", order.getFailReason());
            log.error("  grabTime   = {}", order.getGrabTime());
            log.error("[人工处理建议]");
            log.error("  → 确认用户 [{}] 是否已收到优惠券（查 T_COUPON WHERE USER_ID='{}'）",
                    order.getUserId(), order.getUserId());
            log.error("  → 若未收到：手动执行发券 或 退还 {} 积分", order.getPointsUsed());
            log.error("  → 查询SQL: SELECT * FROM T_SECKILL_ORDER WHERE ID={}", orderId);
            log.error("  → 重试任务: SELECT * FROM T_SECKILL_RETRY_TASK WHERE ORDER_ID={}", orderId);

        } catch (Exception e) {
            log.error("[死信] 处理异常 orderId={}", orderId, e);
        } finally {
            // 死信消息必须 ack，不能再回队列
            ack.acknowledge();
            log.error("══════════════════════════════════════════════════");
        }

        // TODO: 对接告警系统（取消注释并注入 alertService）
        // alertService.sendCriticalAlert(
        //     "🚨 秒杀订单死信告警",
        //     "orderId=" + orderId + "，重试3次失败，需人工补偿！",
        //     AlertLevel.CRITICAL
        // );
    }
}

