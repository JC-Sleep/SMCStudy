package com.sc.supplychain.service.impl;

import com.sc.supplychain.config.DeliveryProperties;
import com.sc.supplychain.dto.DeliveryEventMessage;
import com.sc.supplychain.entity.DeliveryOrder;
import com.sc.supplychain.enums.DeliveryStatus;
import com.sc.supplychain.exception.SupplyChainException;
import com.sc.supplychain.mapper.DeliveryOrderMapper;
import com.sc.supplychain.mq.DeliveryEventProducer;
import com.sc.supplychain.service.DeliveryService;
import com.sc.supplychain.util.DeliveryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryServiceImpl implements DeliveryService {

    private final DeliveryOrderMapper deliveryMapper;
    private final DeliveryEventProducer eventProducer;
    private final DeliveryProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFromFulfillment(Long foId, Long whId,
                                      String pickupAddr, BigDecimal pickupLng, BigDecimal pickupLat,
                                      String deliverAddr, BigDecimal deliverLng, BigDecimal deliverLat,
                                      String customerRemark, String maskedPhone) {
        int distance = DeliveryUtil.haversineMeters(pickupLng, pickupLat, deliverLng, deliverLat);
        int eta = Math.max(15, distance / 250);  // 简单估算：250m/分钟（约15km/h）

        DeliveryOrder d = new DeliveryOrder();
        d.setFulfillmentOrderId(foId);
        d.setWarehouseId(whId);
        d.setStatus(DeliveryStatus.PENDING.getCode());
        d.setPickupAddress(pickupAddr);
        d.setPickupLng(pickupLng);
        d.setPickupLat(pickupLat);
        d.setDeliverAddress(deliverAddr);
        d.setDeliverLng(deliverLng);
        d.setDeliverLat(deliverLat);
        d.setDistanceMeters(distance);
        d.setEstimatedMinutes(eta);
        d.setExpectedPickupTime(LocalDateTime.now().plusMinutes(properties.getPickupTimeoutMinutes()));
        d.setExpectedDeliverTime(LocalDateTime.now().plusMinutes(properties.getPickupTimeoutMinutes() + eta));
        d.setPickupCode(DeliveryUtil.genCode6());
        d.setDeliverCode(DeliveryUtil.genCode6());

        // 计费
        double base = properties.getBaseFeeYuan();
        double extraKm = Math.max(0, distance / 1000.0 - properties.getFreeDistanceKm());
        double distFee = extraKm * properties.getDistanceFeePerKm();
        d.setBaseFee(BigDecimal.valueOf(base));
        d.setDistanceFee(BigDecimal.valueOf(distFee));
        d.setReassignCount(0);
        d.setCustomerRemark(customerRemark);
        d.setCustomerPhoneMasked(maskedPhone);
        deliveryMapper.insert(d);

        // 异步派单（DispatchJob 会扫到 PENDING）
        eventProducer.sendCreate(DeliveryEventMessage.created(d.getId(), foId, whId));

        log.info("[配送-创建] deliveryId={} foId={} distance={}m eta={}min pickupCode={}",
                d.getId(), foId, distance, eta, d.getPickupCode());
        return d.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) throw SupplyChainException.notFound("配送单", String.valueOf(deliveryId));
        if (d.getReassignCount() != null && d.getReassignCount() >= properties.getReassignMaxCount()) {
            throw SupplyChainException.illegalStatus("改派次数超过上限，需人工介入");
        }
        int affected = deliveryMapper.incrReassignCount(deliveryId);
        if (affected == 0) throw SupplyChainException.of("改派失败");
        log.warn("[配送-改派] deliveryId={} count={}", deliveryId, (d.getReassignCount() == null ? 1 : d.getReassignCount() + 1));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long deliveryId, String reason) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) return;
        // 只允许 PENDING/GRABBING/ASSIGNED 取消（已经在路上的需走异常工单）
        int affected = deliveryMapper.transition(deliveryId, d.getStatus(), DeliveryStatus.FAILED.getCode());
        if (affected == 0) {
            throw SupplyChainException.illegalStatus("当前状态不允许取消：" + d.getStatus());
        }
        d.setFailReason(reason);
        deliveryMapper.updateById(d);
        log.info("[配送-取消] deliveryId={} reason={}", deliveryId, reason);
    }

    @Override
    public DeliveryOrder get(Long deliveryId) {
        return deliveryMapper.selectById(deliveryId);
    }
}

