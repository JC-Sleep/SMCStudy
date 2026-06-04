package com.sc.supplychain.listener;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.dto.InventoryEventMessage;
import com.sc.supplychain.entity.InventoryLog;
import com.sc.supplychain.enums.InventoryOpType;
import com.sc.supplychain.mapper.InventoryLogMapper;
import com.sc.supplychain.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存异步落库 Listener
 * 消费 inventory.deduct / restore / confirm → DB 更新
 * 手动 ACK + 幂等保护（基于 sc_inventory_log 的 ref_no+op_type）
 *
 * 【B1 修复】Kafka at-least-once 重复消费时，先查 log 表幂等键已存在则直接 ack 跳过。
 * 【B12 修复】affected==0 不再静默吞，throw 让消息进 DLT 由人工介入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryListener {

    private final InventoryMapper inventoryMapper;
    private final InventoryLogMapper logMapper;

    /** 预扣落库：lockedQty += qty, availableQty -= qty */
    @KafkaListener(topics = KafkaConfig.TOPIC_INVENTORY_DEDUCT,
                   groupId = "sc-inventory-deduct",
                   containerFactory = "kafkaListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class)
    public void onDeduct(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        InventoryEventMessage msg = record.value();
        try {
            // ───── B1 幂等检查 ─────
            if (logMapper.countByRefNoAndOpType(msg.getOrderNo(), InventoryOpType.LOCK.getCode()) > 0) {
                log.info("[DEDUCT落库][幂等] 已处理过，跳过 orderNo={} skuId={}",
                        msg.getOrderNo(), msg.getSkuId());
                ack.acknowledge();
                return;
            }

            int affected = inventoryMapper.lockQty(msg.getSkuId(), msg.getWarehouseId(), msg.getQty());
            if (affected == 0) {
                // ───── B12 修复：不再静默吞，throw 让消息进 DLT ─────
                log.error("[DEDUCT落库] DB库存不足或并发冲突，affected=0, skuId={} orderNo={} qty={}",
                        msg.getSkuId(), msg.getOrderNo(), msg.getQty());
                throw new IllegalStateException("DEDUCT affected=0, will retry then DLT, orderNo=" + msg.getOrderNo());
            }
            // 写幂等流水
            writeLog(msg, InventoryOpType.LOCK, msg.getQty());
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
    @Transactional(rollbackFor = Exception.class)
    public void onRestore(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        InventoryEventMessage msg = record.value();
        try {
            // ───── B1 幂等检查 ─────
            if (logMapper.countByRefNoAndOpType(msg.getOrderNo(), InventoryOpType.UNLOCK.getCode()) > 0) {
                log.info("[RESTORE落库][幂等] 已处理过，跳过 orderNo={}", msg.getOrderNo());
                ack.acknowledge();
                return;
            }
            inventoryMapper.unlockQty(msg.getSkuId(), msg.getWarehouseId(), msg.getQty());
            writeLog(msg, InventoryOpType.UNLOCK, msg.getQty());
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
    @Transactional(rollbackFor = Exception.class)
    public void onConfirm(ConsumerRecord<String, InventoryEventMessage> record, Acknowledgment ack) {
        InventoryEventMessage msg = record.value();
        try {
            // ───── B1 幂等检查 ─────
            if (logMapper.countByRefNoAndOpType(msg.getOrderNo(), InventoryOpType.CONFIRM.getCode() + "_MQ") > 0) {
                log.info("[CONFIRM落库][幂等] 已处理过，跳过 orderNo={}", msg.getOrderNo());
                ack.acknowledge();
                return;
            }
            inventoryMapper.confirmDeductQty(msg.getSkuId(), msg.getWarehouseId(), msg.getQty());
            // 注意：service 层 confirmDeduct 已经写过 CONFIRM 流水（按批次），
            // 这里用 CONFIRM_MQ 子类型作幂等键，避免与 service 层流水冲突
            InventoryLog l = new InventoryLog();
            l.setSkuId(msg.getSkuId());
            l.setWarehouseId(msg.getWarehouseId());
            l.setOpType(InventoryOpType.CONFIRM.getCode() + "_MQ");
            l.setDeltaQty(-msg.getQty());
            l.setRefNo(msg.getOrderNo());
            logMapper.insert(l);

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

    /** 写幂等流水（仅 listener 内部使用） */
    private void writeLog(InventoryEventMessage msg, InventoryOpType opType, int qty) {
        InventoryLog l = new InventoryLog();
        l.setSkuId(msg.getSkuId());
        l.setWarehouseId(msg.getWarehouseId());
        l.setOpType(opType.getCode());
        l.setDeltaQty(opType == InventoryOpType.LOCK ? -qty : qty);
        l.setRefNo(msg.getOrderNo());
        logMapper.insert(l);
    }
}
