package com.sc.supplychain.util;

/**
 * Redis Key 常量工具
 *
 * <pre>
 * inventory:available:{warehouseId}:{skuId}  → String（可用库存预扣主键）
 * inventory:locked:{warehouseId}:{skuId}     → String（锁定库存辅助统计）
 * inventory:batch:{warehouseId}:{skuId}      → ZSet（score=inboundTime毫秒，FIFO）
 * inventory:reconcile:diff                   → Set（对账差异 skuId 集合）
 * expiry:warn:set                            → Set（临期批次 batchNo 集合）
 * </pre>
 */
public class RedisKeyUtil {

    private static final String PREFIX = "sc:";

    public static String inventoryAvailable(Long warehouseId, Long skuId) {
        return PREFIX + "inventory:available:" + warehouseId + ":" + skuId;
    }

    public static String inventoryLocked(Long warehouseId, Long skuId) {
        return PREFIX + "inventory:locked:" + warehouseId + ":" + skuId;
    }

    public static String inventoryBatchZSet(Long warehouseId, Long skuId) {
        return PREFIX + "inventory:batch:" + warehouseId + ":" + skuId;
    }

    public static final String RECONCILE_DIFF_SET = PREFIX + "inventory:reconcile:diff";

    public static final String EXPIRY_WARN_SET = PREFIX + "expiry:warn:set";
}

