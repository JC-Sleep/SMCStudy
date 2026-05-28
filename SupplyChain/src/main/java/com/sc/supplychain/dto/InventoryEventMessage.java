package com.sc.supplychain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Kafka 库存事件消息体（DEDUCT / RESTORE / CONFIRM 通用） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryEventMessage implements Serializable {

    /** 事件类型：DEDUCT / RESTORE / CONFIRM */
    private String eventType;

    private Long skuId;

    private Long warehouseId;

    private int qty;

    /** 关联订单号 */
    private String orderNo;

    /** 消息发送时间（Unix ms） */
    private long timestamp;

    public static InventoryEventMessage deduct(Long skuId, Long warehouseId, int qty, String orderNo) {
        return new InventoryEventMessage("DEDUCT", skuId, warehouseId, qty, orderNo, System.currentTimeMillis());
    }

    public static InventoryEventMessage restore(Long skuId, Long warehouseId, int qty, String orderNo) {
        return new InventoryEventMessage("RESTORE", skuId, warehouseId, qty, orderNo, System.currentTimeMillis());
    }

    public static InventoryEventMessage confirm(Long skuId, Long warehouseId, int qty, String orderNo) {
        return new InventoryEventMessage("CONFIRM", skuId, warehouseId, qty, orderNo, System.currentTimeMillis());
    }
}
