package com.sc.supplychain.service;

import com.sc.supplychain.entity.DeliveryOrder;
import com.sc.supplychain.entity.Rider;

import java.math.BigDecimal;
import java.util.List;

public interface RiderService {

    /** 上线/下线 */
    void online(Long riderId);
    void offline(Long riderId);

    /** 心跳 + 位置 */
    void heartbeat(Long riderId, BigDecimal lng, BigDecimal lat);

    /** 接受派单（智能派单收到推送后） */
    void acceptAssign(Long riderId, Long deliveryId);

    /** 拒单（一单只能拒一次，会转入抢单池） */
    void reject(Long riderId, Long deliveryId);

    /** 抢单 */
    boolean grab(Long riderId, Long deliveryId);

    /** 扫描取货码 → IN_TRANSIT */
    void pickup(Long riderId, Long deliveryId, String code);

    /** 扫描签收码 → DELIVERED */
    void delivered(Long riderId, Long deliveryId, String code);

    /** 上报异常 */
    void reportException(Long riderId, Long deliveryId, String type, String detail);

    Rider get(Long riderId);
    List<DeliveryOrder> myOrders(Long riderId);
}

