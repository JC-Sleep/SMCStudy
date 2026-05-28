package com.sc.supplychain.service;

import com.sc.supplychain.dto.BatchAllocation;
import com.sc.supplychain.dto.request.InboundRequest;
import com.sc.supplychain.entity.Inventory;

import java.util.List;

/** 分布式库存服务（Redis 预扣 + MQ 异步落库 + 定时对账） */
public interface InventoryService {

    /**
     * 入库：写批次记录，Redis INCRBY，DB 增加库存，ZSet 添加批次 FIFO
     */
    void inbound(InboundRequest req);

    /**
     * 库存预扣（下单时调用）
     * 使用 Lua Script 保证原子性防超卖
     * @return 扣减后 Redis 剩余可用量（-1 = 库存不足）
     */
    long lockStock(Long warehouseId, Long skuId, int qty, String orderNo);

    /**
     * 释放预扣库存（订单取消/超时）
     */
    void unlockStock(Long warehouseId, Long skuId, int qty, String orderNo);

    /**
     * 出库确认（支付后拣货出库）
     * FIFO 分配批次 → 扣减批次 remain_qty → 更新 DB lockedQty/totalQty
     */
    List<BatchAllocation> confirmDeduct(Long warehouseId, Long skuId, int qty, String orderNo);

    /**
     * 查仓库级库存
     */
    Inventory getInventory(Long warehouseId, Long skuId);

    /**
     * 查 Redis 实时可用量
     */
    long getAvailableFromRedis(Long warehouseId, Long skuId);

    /**
     * Redis 库存初始化/预热（入库后同步到 Redis，或系统启动时从 DB 加载）
     */
    void warmupRedisStock(Long warehouseId, Long skuId);

    /**
     * 库存对账（定时任务调用：对比 Redis vs DB，差异写 reconcile:diff Set）
     */
    void reconcile();
}
