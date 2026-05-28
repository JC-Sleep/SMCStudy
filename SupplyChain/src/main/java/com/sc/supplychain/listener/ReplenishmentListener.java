package com.sc.supplychain.listener;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.dto.ReplenishmentMessage;
import com.sc.supplychain.entity.ReplenishmentOrder;
import com.sc.supplychain.enums.ReplenishmentStatus;
import com.sc.supplychain.mapper.ReplenishmentOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 补货触发 Listener：生成补货单 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplenishmentListener {

    private final ReplenishmentOrderMapper replenishmentOrderMapper;

    @KafkaListener(topics = KafkaConfig.TOPIC_REPLENISHMENT_TRIGGER,
                   groupId = "sc-replenishment")
    public void onTrigger(ConsumerRecord<String, ReplenishmentMessage> record, Acknowledgment ack) {
        ReplenishmentMessage msg = record.value();
        try {
            ReplenishmentOrder order = new ReplenishmentOrder();
            order.setRuleId(msg.getRuleId());
            order.setSkuId(msg.getSkuId());
            order.setWarehouseId(msg.getWarehouseId());
            order.setQty(msg.getReplenishQty());
            order.setStatus(ReplenishmentStatus.PENDING.getCode());
            order.setTriggerTime(LocalDateTime.now());
            replenishmentOrderMapper.insert(order);
            ack.acknowledge();
            log.info("[补货单生成] skuId={} warehouseId={} qty={} orderId={}",
                    msg.getSkuId(), msg.getWarehouseId(), msg.getReplenishQty(), order.getId());
        } catch (Exception e) {
            log.error("[补货触发] 生成补货单异常 msg={}", msg, e);
            throw e;
        }
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_REPLENISHMENT_TRIGGER_DLT,
                   groupId = "sc-replenishment-dlt")
    public void onTriggerDlt(ConsumerRecord<String, ReplenishmentMessage> record, Acknowledgment ack) {
        log.error("[DLT告警] 补货触发消息最终失败！key={} value={}", record.key(), record.value());
        ack.acknowledge();
    }
}
