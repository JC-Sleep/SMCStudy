package com.sc.supplychain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Kafka 配送事件消息 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryEventMessage {
    /** CREATE / DELIVERED / RETURNED / ASSIGNED */
    private String eventType;
    private Long deliveryId;
    private Long fulfillmentOrderId;
    private Long riderId;
    private Long warehouseId;

    public static DeliveryEventMessage created(Long deliveryId, Long foId, Long whId) {
        return new DeliveryEventMessage("CREATE", deliveryId, foId, null, whId);
    }
    public static DeliveryEventMessage delivered(Long deliveryId, Long foId, Long riderId) {
        return new DeliveryEventMessage("DELIVERED", deliveryId, foId, riderId, null);
    }
    public static DeliveryEventMessage returned(Long deliveryId, Long foId, Long riderId) {
        return new DeliveryEventMessage("RETURNED", deliveryId, foId, riderId, null);
    }
}

