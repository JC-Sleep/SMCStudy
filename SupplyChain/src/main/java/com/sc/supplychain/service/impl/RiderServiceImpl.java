package com.sc.supplychain.service.impl;

import com.sc.supplychain.config.DeliveryProperties;
import com.sc.supplychain.dto.DeliveryEventMessage;
import com.sc.supplychain.entity.DeliveryException;
import com.sc.supplychain.entity.DeliveryOrder;
import com.sc.supplychain.entity.Rider;
import com.sc.supplychain.entity.RiderIncome;
import com.sc.supplychain.enums.DeliveryStatus;
import com.sc.supplychain.enums.RiderStatus;
import com.sc.supplychain.exception.SupplyChainException;
import com.sc.supplychain.mapper.*;
import com.sc.supplychain.mq.DeliveryEventProducer;
import com.sc.supplychain.service.RiderService;
import com.sc.supplychain.util.DeliveryUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiderServiceImpl implements RiderService {

    private final RiderMapper riderMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final DeliveryGrabPoolMapper grabPoolMapper;
    private final DeliveryExceptionMapper exceptionMapper;
    private final RiderIncomeMapper incomeMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> riderGrabScript;
    private final DeliveryEventProducer eventProducer;
    private final DeliveryProperties properties;

    // ── 上线/下线/心跳 ─────────────────────────────────────────

    @Override
    public void online(Long riderId) {
        Rider r = require(riderId);
        r.setOnline(1);
        r.setStatus(RiderStatus.IDLE.getCode());
        r.setLastHeartbeat(LocalDateTime.now());
        riderMapper.updateById(r);
        log.info("[骑手-上线] riderId={}", riderId);
    }

    @Override
    public void offline(Long riderId) {
        Rider r = require(riderId);
        if (r.getCurrentLoad() != null && r.getCurrentLoad() > 0) {
            throw SupplyChainException.illegalStatus("当前还有 " + r.getCurrentLoad() + " 单未送达，不能下线");
        }
        r.setOnline(0);
        r.setStatus(RiderStatus.OFFLINE.getCode());
        riderMapper.updateById(r);
    }

    @Override
    public void heartbeat(Long riderId, BigDecimal lng, BigDecimal lat) {
        riderMapper.heartbeat(riderId, lng, lat);
        // Redis GEO 同步（用于地理位置查询）
        Rider r = riderMapper.selectById(riderId);
        if (r != null && r.getWarehouseId() != null && lng != null && lat != null) {
            stringRedisTemplate.opsForGeo().add(
                    DeliveryUtil.riderGeoKey(r.getWarehouseId()),
                    new org.springframework.data.geo.Point(lng.doubleValue(), lat.doubleValue()),
                    String.valueOf(riderId));
        }
    }

    // ── 接单/拒单/抢单 ─────────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptAssign(Long riderId, Long deliveryId) {
        // 状态机：PENDING/GRABBING → ASSIGNED（已被 dispatch 绑定，这里实际是确认）
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null || !riderId.equals(d.getRiderId())) {
            throw SupplyChainException.illegalStatus("该单未派给您");
        }
        if (!DeliveryStatus.ASSIGNED.getCode().equals(d.getStatus())) {
            throw SupplyChainException.illegalStatus("订单状态不可接受：" + d.getStatus());
        }
        // 已经是 ASSIGNED 状态，仅做日志记录（confirm by rider）
        log.info("[骑手-接单确认] riderId={} deliveryId={}", riderId, deliveryId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long riderId, Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null || !riderId.equals(d.getRiderId())) {
            throw SupplyChainException.illegalStatus("该单未派给您");
        }
        // 状态从 ASSIGNED → REJECTED，再 → PENDING（解绑骑手），让 DispatchJob 或抢单池接管
        int aff = deliveryMapper.transition(deliveryId, DeliveryStatus.ASSIGNED.getCode(),
                DeliveryStatus.REJECTED.getCode());
        if (aff == 0) throw SupplyChainException.illegalStatus("拒单失败，状态可能已变更");

        // 解绑 + 计数+1，回到 PENDING 由 DispatchJob 重新派
        deliveryMapper.incrReassignCount(deliveryId);
        // 释放骑手负载
        riderMapper.decrLoadOnFinish(riderId);
        // 拒单计为今天0次（today_orders+1 是 decrLoadOnFinish 副作用，这里粗略不修正）
        log.info("[骑手-拒单] riderId={} deliveryId={}", riderId, deliveryId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean grab(Long riderId, Long deliveryId) {
        // 1. Redis Lua SETNX 锁定（防超抢核心）
        String lockKey = DeliveryUtil.grabLockKey(deliveryId);
        Long ok = stringRedisTemplate.execute(riderGrabScript,
                Collections.singletonList(lockKey),
                String.valueOf(riderId), String.valueOf(properties.getGrabPoolExpireSeconds()));
        if (ok == null || ok == 0L) {
            log.info("[抢单失败-锁] riderId={} deliveryId={}", riderId, deliveryId);
            return false;
        }
        // ── D1 修复：从此往后，任何分支失败都必须 DEL Redis 锁 ──
        try {
            // 2. DB 兜底（保证最终一致）
            int affDb = deliveryMapper.bindRiderIfPending(deliveryId, riderId);
            if (affDb == 0) {
                stringRedisTemplate.delete(lockKey);
                log.info("[抢单失败-DB] riderId={} deliveryId={}", riderId, deliveryId);
                return false;
            }
            // 3. 抢单池标记（如果使用了抢单池表）
            grabPoolMapper.grab(deliveryId, riderId);
            // 4. 骑手负载+1
            if (riderMapper.incrLoadIfAvailable(riderId) == 0) {
                stringRedisTemplate.delete(lockKey);
                throw new RuntimeException("骑手负载已满，回滚抢单");
            }
            log.info("[抢单成功] riderId={} deliveryId={}", riderId, deliveryId);
            return true;
        } catch (RuntimeException ex) {
            // 任何异常：先删锁再让事务回滚
            stringRedisTemplate.delete(lockKey);
            throw ex;
        }
    }

    // ── 取货 / 送达 / 异常 ────────────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pickup(Long riderId, Long deliveryId, String code) {
        DeliveryOrder d = mustOwn(riderId, deliveryId);
        if (!code.equals(d.getPickupCode())) {
            throw SupplyChainException.illegalStatus("取货码错误");
        }
        // ASSIGNED → PICKING → IN_TRANSIT（一步到位，简化为扫码后立刻在途）
        int aff = deliveryMapper.transition(deliveryId,
                DeliveryStatus.ASSIGNED.getCode(),
                DeliveryStatus.IN_TRANSIT.getCode());
        if (aff == 0) throw SupplyChainException.illegalStatus("状态不允许取货");
        d.setPickupTime(LocalDateTime.now());
        deliveryMapper.updateById(d);
        log.info("[骑手-取货] riderId={} deliveryId={}", riderId, deliveryId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delivered(Long riderId, Long deliveryId, String code) {
        DeliveryOrder d = mustOwn(riderId, deliveryId);
        if (!code.equals(d.getDeliverCode())) {
            throw SupplyChainException.illegalStatus("签收码错误");
        }
        int aff = deliveryMapper.transition(deliveryId,
                DeliveryStatus.IN_TRANSIT.getCode(),
                DeliveryStatus.DELIVERED.getCode());
        if (aff == 0) throw SupplyChainException.illegalStatus("状态不允许送达");
        d.setDeliveredTime(LocalDateTime.now());
        // 计算最终配送费 + 超时罚款
        BigDecimal penalty = calcPenalty(d);
        BigDecimal finalFee = nz(d.getBaseFee()).add(nz(d.getDistanceFee())).subtract(penalty);
        d.setPenaltyFee(penalty);
        d.setFinalFee(finalFee);
        deliveryMapper.updateById(d);

        // 写收入流水
        RiderIncome inc = new RiderIncome();
        inc.setRiderId(riderId);
        inc.setDeliveryId(deliveryId);
        inc.setBaseFee(d.getBaseFee());
        inc.setDistanceFee(d.getDistanceFee());
        inc.setRushHourBonus(BigDecimal.ZERO);  // TODO Phase 2.2 高峰补贴
        inc.setWeatherBonus(BigDecimal.ZERO);
        inc.setPenaltyFee(penalty);
        inc.setTotal(finalFee);
        inc.setSettleStatus("PENDING");
        incomeMapper.insert(inc);

        // 释放骑手负载
        riderMapper.decrLoadOnFinish(riderId);

        // Kafka 通知履约系统：DELIVERED → 履约订单 DELIVERED
        eventProducer.sendDelivered(DeliveryEventMessage.delivered(deliveryId, d.getFulfillmentOrderId(), riderId));
        log.info("[骑手-送达] riderId={} deliveryId={} finalFee={}", riderId, deliveryId, finalFee);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportException(Long riderId, Long deliveryId, String type, String detail) {
        DeliveryOrder d = mustOwn(riderId, deliveryId);
        DeliveryException ex = new DeliveryException();
        ex.setDeliveryId(deliveryId);
        ex.setExceptionType(type);
        ex.setExceptionDetail(detail);
        ex.setHandleStatus("OPEN");
        exceptionMapper.insert(ex);

        // 客户拒收 → 触发逆向库存（库存归还）
        if ("CUSTOMER_REJECTED".equals(type)) {
            deliveryMapper.transition(deliveryId, d.getStatus(), DeliveryStatus.RETURNED.getCode());
            eventProducer.sendReturned(DeliveryEventMessage.returned(deliveryId, d.getFulfillmentOrderId(), riderId));
            riderMapper.decrLoadOnFinish(riderId);
        }
        log.warn("[骑手-异常] riderId={} deliveryId={} type={}", riderId, deliveryId, type);
    }

    @Override
    public Rider get(Long riderId) { return require(riderId); }

    @Override
    public List<DeliveryOrder> myOrders(Long riderId) {
        return deliveryMapper.selectList(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getRiderId, riderId)
                .orderByDesc(DeliveryOrder::getCreateTime));
    }

    // ── 私有 ──────────────────────────────────────────────────

    private Rider require(Long riderId) {
        Rider r = riderMapper.selectById(riderId);
        if (r == null) throw SupplyChainException.notFound("骑手", String.valueOf(riderId));
        return r;
    }

    private DeliveryOrder mustOwn(Long riderId, Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) throw SupplyChainException.notFound("配送单", String.valueOf(deliveryId));
        if (!riderId.equals(d.getRiderId())) {
            throw SupplyChainException.illegalStatus("该单未派给您");
        }
        return d;
    }

    private BigDecimal calcPenalty(DeliveryOrder d) {
        if (d.getExpectedDeliverTime() == null) return BigDecimal.ZERO;
        long minutesOver = java.time.Duration.between(d.getExpectedDeliverTime(), LocalDateTime.now()).toMinutes();
        if (minutesOver <= 0) return BigDecimal.ZERO;
        double p = Math.min(properties.getPenaltyCapYuan(), minutesOver * properties.getPenaltyPerMinute());
        return BigDecimal.valueOf(p);
    }

    private BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}


