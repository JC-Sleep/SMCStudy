package com.sc.supplychain.listener;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.dto.InventoryEventMessage;
import com.sc.supplychain.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 库存异步落库 Listener
 * 消费 inventory.deduct / restore / confirm → DB 更新
 * 手动 ACK + 重试3次后进 DLT（死信队列）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryListener {

    private final InventoryMapper inventoryMapper;

    /** 预扣落库：lockedQty += qty, availableQty -= qty */
    @KafkaListener(topics = KafkaConfig.TOPIC_INVENTORY_DEDUCT,
                   groupId = "sc-inventory-deduct",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onDeduct(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        InventoryEventMessage msg = record.value();
        try {
            int affected = inventoryMapper.lockQty(msg.getSkuId(), msg.getWarehouseId(), msg.getQty());
            if (affected == 0) {
                log.warn("[DEDUCT落库] DB库存不足（可能已对账修复）skuId={} orderNo={}",
                        msg.getSkuId(), msg.getOrderNo());
            }
            ack.acknowledge();
            log.debug("[DEDUCT落库] 成功 skuId={} qty={} orderNo={}",
                    msg.getSkuId(), msg.getQty(), msg.getOrderNo());
        } catch (Exception e) {
            log.error("[DEDUCT落库] 异常 skuId={} orderNo={} err={}",
                    msg.getSkuId(), msg.getOrderNo(), e.getMessage());
            // 不 ack 触发重试，超出重试次数后进 DLT
            throw e;
        }
    }

    /** 释放落库：lockedQty -= qty, availableQty += qty */
    @KafkaListener(topics = KafkaConfig.TOPIC_INVENTORY_RESTORE,
                   groupId = "sc-inventory-restore")
    public void onRestore(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        InventoryEventMessage msg = record.value();
        try {
            inventoryMapper.unlockQty(msg.getSkuId(), msg.getWarehouseId(), msg.getQty());
            ack.acknowledge();
            log.debug("[RESTORE落库] skuId={} qty={} orderNo={}",
                    msg.getSkuId(), msg.getQty(), msg.getOrderNo());
        } catch (Exception e) {
            log.error("[RESTORE落库] 异常 msg={}", msg, e);
            throw e;
        }
    }

    /** 出库确认：lockedQty -= qty, totalQty -= qty */
    @KafkaListener(topics = KafkaConfig.TOPIC_INVENTORY_CONFIRM,
                   groupId = "sc-inventory-confirm")
    public void onConfirm(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        InventoryEventMessage msg = record.value();
        try {
            inventoryMapper.confirmDeductQty(msg.getSkuId(), msg.getWarehouseId(), msg.getQty());
            ack.acknowledge();
            log.debug("[CONFIRM落库] skuId={} qty={} orderNo={}",
                    msg.getSkuId(), msg.getQty(), msg.getOrderNo());
        } catch (Exception e) {
            log.error("[CONFIRM落库] 异常 msg={}", msg, e);
            throw e;
        }
    }

    /** 死信队列消费（人工告警） */
    @KafkaListener(topics = KafkaConfig.TOPIC_INVENTORY_DEDUCT_DLT,
                   groupId = "sc-inventory-dlt")
    public void onDeductDlt(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        log.error("[DLT告警] 库存扣减消息最终失败，需人工补偿！key={} value={}", record.key(), record.value());
        ack.acknowledge();
    }
}
