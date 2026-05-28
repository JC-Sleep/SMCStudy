package com.sc.supplychain.mq;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.dto.InventoryEventMessage;
import com.sc.supplychain.dto.ReplenishmentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 库存/补货 Kafka 生产者 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendDeduct(InventoryEventMessage msg) {
        send(KafkaConfig.TOPIC_INVENTORY_DEDUCT, msg.getOrderNo(), msg);
    }

    public void sendRestore(InventoryEventMessage msg) {
        send(KafkaConfig.TOPIC_INVENTORY_RESTORE, msg.getOrderNo(), msg);
    }

    public void sendConfirm(InventoryEventMessage msg) {
        send(KafkaConfig.TOPIC_INVENTORY_CONFIRM, msg.getOrderNo(), msg);
    }

    public void sendReplenishmentTrigger(ReplenishmentMessage msg) {
        send(KafkaConfig.TOPIC_REPLENISHMENT_TRIGGER,
                msg.getSkuId() + "-" + msg.getWarehouseId(), msg);
    }

    private void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload)
                .addCallback(
                        success -> log.debug("[MQ发送] topic={} key={}", topic, key),
                        failure -> log.error("[MQ发送失败] topic={} key={} err={}", topic, key, failure.getMessage())
                );
    }
}
