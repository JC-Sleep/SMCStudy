package com.sc.supplychain.listener;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.dto.DeliveryEventMessage;
import com.sc.supplychain.entity.FulfillmentOrder;
import com.sc.supplychain.enums.FulfillmentOrderStatus;
import com.sc.supplychain.mapper.FulfillmentOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 配送结果回写履约 + 库存逆向
 *
 * - DELIVERED 事件：履约订单 OUTBOUND/DELIVERING → DELIVERED
 * - RETURNED 事件：触发逆向库存（unlockStock 或 inbound 修复）TODO Phase 2.2
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryEventListener {

    private final FulfillmentOrderMapper orderMapper;

    @KafkaListener(topics = KafkaConfig.TOPIC_DELIVERY_DELIVERED, groupId = "sc-delivery-delivered")
    public void onDelivered(ConsumerRecord<String, DeliveryEventMessage> record, Acknowledgment ack) {
        DeliveryEventMessage msg = record.value();
        try {
            FulfillmentOrder o = orderMapper.selectOne(new LambdaQueryWrapper<FulfillmentOrder>()
                    .eq(FulfillmentOrder::getId, msg.getFulfillmentOrderId()));
            if (o == null) { ack.acknowledge(); return; }
            // 简单写：状态推到 DELIVERED（生产应做幂等检查）
            o.setStatus(FulfillmentOrderStatus.DELIVERED.getCode());
            orderMapper.updateById(o);
            log.info("[配送回写] 履约订单 DELIVERED foId={}", msg.getFulfillmentOrderId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[配送回写] 异常 msg={}", msg, e);
            throw e;
        }
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_DELIVERY_RETURNED, groupId = "sc-delivery-returned")
    public void onReturned(ConsumerRecord<String, DeliveryEventMessage> record, Acknowledgment ack) {
        DeliveryEventMessage msg = record.value();
        log.warn("[配送-客户拒收] 触发逆向库存(TODO) msg={}", msg);
        // TODO Phase 2.2: inventoryService.inbound(...) 把货退回库存
        ack.acknowledge();
    }
}

