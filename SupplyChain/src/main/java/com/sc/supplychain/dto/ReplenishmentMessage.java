package com.sc.supplychain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Kafka 补货触发消息体 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplenishmentMessage implements Serializable {

    private Long ruleId;
    private Long skuId;
    private Long warehouseId;
    private int triggerQty;
    private int replenishQty;
    private long timestamp;
}
