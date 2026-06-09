package com.sc.supplychain.mq;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.dto.DeliveryEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendCreate(DeliveryEventMessage msg) {
        kafkaTemplate.send(KafkaConfig.TOPIC_DELIVERY_CREATE, String.valueOf(msg.getDeliveryId()), msg);
    }
    public void sendDelivered(DeliveryEventMessage msg) {
        kafkaTemplate.send(KafkaConfig.TOPIC_DELIVERY_DELIVERED, String.valueOf(msg.getDeliveryId()), msg);
    }
    public void sendReturned(DeliveryEventMessage msg) {
        kafkaTemplate.send(KafkaConfig.TOPIC_DELIVERY_RETURNED, String.valueOf(msg.getDeliveryId()), msg);
    }
    public void sendAssigned(DeliveryEventMessage msg) {
        kafkaTemplate.send(KafkaConfig.TOPIC_DELIVERY_ASSIGNED, String.valueOf(msg.getDeliveryId()), msg);
    }
}

