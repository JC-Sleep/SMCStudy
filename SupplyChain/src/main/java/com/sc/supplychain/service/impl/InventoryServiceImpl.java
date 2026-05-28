package com.sc.supplychain.service.impl;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.config.SupplyChainProperties;
import com.sc.supplychain.dto.BatchAllocation;
import com.sc.supplychain.dto.InventoryEventMessage;
import com.sc.supplychain.dto.request.InboundRequest;
import com.sc.supplychain.entity.Inventory;
import com.sc.supplychain.entity.InventoryBatch;
import com.sc.supplychain.entity.InventoryLog;
import com.sc.supplychain.enums.BatchStatus;
import com.sc.supplychain.enums.InventoryOpType;
import com.sc.supplychain.exception.SupplyChainException;
import com.sc.supplychain.mapper.InventoryBatchMapper;
import com.sc.supplychain.mapper.InventoryLogMapper;
import com.sc.supplychain.mapper.InventoryMapper;
import com.sc.supplychain.mq.InventoryEventProducer;
import com.sc.supplychain.service.InventoryService;
import com.sc.supplychain.util.RedisKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final InventoryBatchMapper batchMapper;
    private final InventoryLogMapper logMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> inventoryLockScript;
    private final InventoryEventProducer eventProducer;
    private final SupplyChainProperties properties;

    // ── 入库 ─────────────────────────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inbound(InboundRequest req) {
        Long skuId = req.getSkuId();
        Long warehouseId = req.getWarehouseId();
        int qty = req.getQty();

        // 1. 生成批次号
        String batchNo = req.getBatchNo() != null ? req.getBatchNo()
                : "BATCH-" + DateUtil.format(java.util.Date.from(
                LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toInstant()),
                "yyyyMMdd-HHmmss") + "-" + skuId;

        // 2. 写批次记录
        InventoryBatch batch = new InventoryBatch();
        batch.setBatchNo(batchNo);
        batch.setSkuId(skuId);
        batch.setWarehouseId(warehouseId);
        batch.setInboundQty(qty);
        batch.setRemainQty(qty);
        batch.setProductDate(req.getProductDate());
        batch.setExpireDate(req.getExpireDate());
        batch.setInboundTime(LocalDateTime.now());
        batch.setStatus(BatchStatus.NORMAL.getCode());
        batchMapper.insert(batch);

        // 3. DB 库存 upsert
        Inventory inv = getOrCreateInventory(warehouseId, skuId);
        int before = inv.getAvailableQty();
        int affected = inventoryMapper.addQty(skuId, warehouseId, qty);
        if (affected == 0) {
            throw SupplyChainException.of("入库更新DB库存失败 skuId=" + skuId);
        }

        // 4. Redis INCRBY + ZSet 添加批次（FIFO score = inboundTime millis）
        String redisKey = RedisKeyUtil.inventoryAvailable(warehouseId, skuId);
        redisTemplate.opsForValue().increment(redisKey, qty);
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(RedisKeyUtil.inventoryBatchZSet(warehouseId, skuId), batchNo, score);

        // 5. 写流水
        writeLog(skuId, warehouseId, batchNo, InventoryOpType.INBOUND, before, before + qty, qty, batchNo);
        log.info("[入库] skuId={} warehouseId={} qty={} batchNo={}", skuId, warehouseId, qty, batchNo);
    }

    // ── 预扣（Lua 原子防超卖） ─────────────────────────────────────────

    @Override
    public long lockStock(Long warehouseId, Long skuId, int qty, String orderNo) {
        String key = RedisKeyUtil.inventoryAvailable(warehouseId, skuId);
        Long result = redisTemplate.execute(
                inventoryLockScript,
                Collections.singletonList(key),
                (long) qty);
        if (result == null || result < 0) {
            log.warn("[预扣失败] 库存不足 skuId={} warehouseId={} qty={}", skuId, warehouseId, qty);
            throw SupplyChainException.stockInsufficient(skuId);
        }
        // 异步落库：Kafka sc.inventory.deduct
        eventProducer.sendDeduct(InventoryEventMessage.deduct(skuId, warehouseId, qty, orderNo));
        log.info("[预扣] skuId={} qty={} 剩余={} orderNo={}", skuId, qty, result, orderNo);
        return result;
    }

    // ── 释放预扣 ─────────────────────────────────────────────────────

    @Override
    public void unlockStock(Long warehouseId, Long skuId, int qty, String orderNo) {
        String key = RedisKeyUtil.inventoryAvailable(warehouseId, skuId);
        redisTemplate.opsForValue().increment(key, qty);
        // 异步落库
        eventProducer.sendRestore(InventoryEventMessage.restore(skuId, warehouseId, qty, orderNo));
        log.info("[释放预扣] skuId={} qty={} orderNo={}", skuId, qty, orderNo);
    }

    // ── 出库确认（FIFO 批次分配） ────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BatchAllocation> confirmDeduct(Long warehouseId, Long skuId, int qty, String orderNo) {
        // 1. FIFO 批次分配
        List<BatchAllocation> allocations = allocateFifo(warehouseId, skuId, qty);

        // 2. 逐批扣减 DB remain_qty
        for (BatchAllocation a : allocations) {
            int affected = batchMapper.deductBatchRemain(a.getBatchId(), a.getAllocateQty());
            if (affected == 0) {
                throw SupplyChainException.of("FIFO批次扣减失败 batchId=" + a.getBatchId());
            }
            writeLog(skuId, warehouseId, a.getBatchNo(), InventoryOpType.CONFIRM,
                    -1, -1, -a.getAllocateQty(), orderNo);
        }

        // 3. MQ 异步更新 DB lockedQty/totalQty
        eventProducer.sendConfirm(InventoryEventMessage.confirm(skuId, warehouseId, qty, orderNo));
        log.info("[出库确认] skuId={} qty={} batches={} orderNo={}", skuId, qty, allocations.size(), orderNo);
        return allocations;
    }

    // ── FIFO 批次分配 ────────────────────────────────────────────────

    private List<BatchAllocation> allocateFifo(Long warehouseId, Long skuId, int needQty) {
        List<InventoryBatch> batches = batchMapper.selectFifoBatches(skuId, warehouseId);
        List<BatchAllocation> result = new ArrayList<>();
        int remaining = needQty;
        for (InventoryBatch b : batches) {
            if (remaining <= 0) break;
            int take = Math.min(b.getRemainQty(), remaining);
            result.add(new BatchAllocation(b.getId(), b.getBatchNo(), take));
            remaining -= take;
        }
        if (remaining > 0) {
            throw SupplyChainException.of("FIFO分配失败：批次总量不足，需求=" + needQty + " 不足=" + remaining);
        }
        return result;
    }

    // ── 查询 ─────────────────────────────────────────────────────────

    @Override
    public Inventory getInventory(Long warehouseId, Long skuId) {
        return inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWarehouseId, warehouseId)
                .eq(Inventory::getSkuId, skuId));
    }

    @Override
    public long getAvailableFromRedis(Long warehouseId, Long skuId) {
        Object val = redisTemplate.opsForValue().get(RedisKeyUtil.inventoryAvailable(warehouseId, skuId));
        if (val == null) return 0L;
        return Long.parseLong(val.toString());
    }

    // ── Redis 预热 ────────────────────────────────────────────────────

    @Override
    public void warmupRedisStock(Long warehouseId, Long skuId) {
        Inventory inv = getInventory(warehouseId, skuId);
        if (inv == null) return;
        String key = RedisKeyUtil.inventoryAvailable(warehouseId, skuId);
        redisTemplate.opsForValue().set(key, (long) inv.getAvailableQty());
        log.info("[预热] Redis库存 skuId={} warehouseId={} qty={}", skuId, warehouseId, inv.getAvailableQty());
    }

    // ── 定时对账 ────────────────────────────────────────────────────

    @Override
    public void reconcile() {
        List<Inventory> allInv = inventoryMapper.selectList(null);
        int diffThreshold = properties.getReconcile().getDiffThreshold();
        for (Inventory inv : allInv) {
            long redisQty = getAvailableFromRedis(inv.getWarehouseId(), inv.getSkuId());
            long dbQty = inv.getAvailableQty();
            long diff = Math.abs(redisQty - dbQty);
            if (diff > diffThreshold) {
                log.warn("[对账差异] skuId={} warehouseId={} Redis={} DB={} diff={}",
                        inv.getSkuId(), inv.getWarehouseId(), redisQty, dbQty, diff);
                // 以 DB 为准修复 Redis
                String key = RedisKeyUtil.inventoryAvailable(inv.getWarehouseId(), inv.getSkuId());
                redisTemplate.opsForValue().set(key, dbQty);
                redisTemplate.opsForSet().add(RedisKeyUtil.RECONCILE_DIFF_SET,
                        inv.getWarehouseId() + ":" + inv.getSkuId());
                writeLog(inv.getSkuId(), inv.getWarehouseId(), null,
                        InventoryOpType.RECONCILE_FIX,
                        (int) redisQty, (int) dbQty, (int)(dbQty - redisQty), "RECONCILE");
            }
        }
    }

    // ── 私有工具 ────────────────────────────────────────────────────

    private Inventory getOrCreateInventory(Long warehouseId, Long skuId) {
        Inventory inv = getInventory(warehouseId, skuId);
        if (inv == null) {
            inv = new Inventory();
            inv.setSkuId(skuId);
            inv.setWarehouseId(warehouseId);
            inv.setAvailableQty(0);
            inv.setLockedQty(0);
            inv.setTotalQty(0);
            inventoryMapper.insert(inv);
        }
        return inv;
    }

    private void writeLog(Long skuId, Long warehouseId, String batchNo,
                          InventoryOpType opType, int before, int after, int delta, String refNo) {
        InventoryLog log2 = new InventoryLog();
        log2.setSkuId(skuId);
        log2.setWarehouseId(warehouseId);
        log2.setBatchNo(batchNo);
        log2.setOpType(opType.getCode());
        log2.setQtyBefore(before < 0 ? null : before);
        log2.setQtyAfter(after < 0 ? null : after);
        log2.setDeltaQty(delta);
        log2.setRefNo(refNo);
        logMapper.insert(log2);
    }
}
