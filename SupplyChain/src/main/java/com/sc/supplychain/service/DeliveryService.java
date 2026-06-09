package com.sc.supplychain.service;

import com.sc.supplychain.entity.DeliveryOrder;

import java.math.BigDecimal;

public interface DeliveryService {

    /** 履约 OUTBOUND 后调用：根据履约订单创建配送单（PENDING 状态，待派单） */
    Long createFromFulfillment(Long fulfillmentOrderId, Long warehouseId,
                               String pickupAddr, BigDecimal pickupLng, BigDecimal pickupLat,
                               String deliverAddr, BigDecimal deliverLng, BigDecimal deliverLat,
                               String customerRemark, String maskedPhone);

    /** 强制改派 */
    void reassign(Long deliveryId);

    /** 取消配送（业务取消订单时联动） */
    void cancel(Long deliveryId, String reason);

    DeliveryOrder get(Long deliveryId);
}

