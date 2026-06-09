package com.sc.supplychain.service.impl;

import com.sc.supplychain.config.DeliveryProperties;
import com.sc.supplychain.entity.DeliveryOrder;
import com.sc.supplychain.entity.Rider;
import com.sc.supplychain.mapper.DeliveryOrderMapper;
import com.sc.supplychain.mapper.RiderMapper;
import com.sc.supplychain.service.DispatchService;
import com.sc.supplychain.util.DeliveryUtil;
import com.sc.supplychain.websocket.RiderWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 派单算法 V1：距离 + 评分 + 负载 加权评分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchServiceImpl implements DispatchService {

    private final RiderMapper riderMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final DeliveryProperties properties;
    private final RiderWebSocketHandler wsHandler;

    @Override
    public Rider pickRider(Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null || d.getWarehouseId() == null) return null;

        List<Rider> candidates = riderMapper.selectAvailable(d.getWarehouseId());
        if (candidates.isEmpty()) return null;

        Rider best = null;
        double bestScore = -1;
        for (Rider r : candidates) {
            int distance = DeliveryUtil.haversineMeters(
                    r.getCurrentLng(), r.getCurrentLat(), d.getPickupLng(), d.getPickupLat());
            // 距离分：广播半径外为 0
            double distScore = Math.max(0, 1.0 - (double) distance / properties.getBroadcastRadiusMeters());
            // 负载分：剩余空位比例
            double loadScore = 1.0 - (double) r.getCurrentLoad() / Math.max(1, r.getMaxParallel());
            // 评分（5 满分归一化）
            double rating = r.getRating() == null ? 1.0 : r.getRating().doubleValue() / 5.0;

            double score = distScore * properties.getWeightDistance()
                    + loadScore * properties.getWeightLoad()
                    + rating * properties.getWeightRating();
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean dispatch(Long deliveryId) {
        Rider rider = pickRider(deliveryId);
        if (rider == null) {
            log.info("[派单] 无可用骑手 deliveryId={}", deliveryId);
            return false;
        }
        // 1. 原子绑定 DB（B3 经验复用）
        int bound = deliveryMapper.bindRiderIfPending(deliveryId, rider.getId());
        if (bound == 0) {
            log.info("[派单] DB 绑定失败（已被其他线程派出） deliveryId={}", deliveryId);
            return false;
        }
        // 2. 增加骑手负载
        int loaded = riderMapper.incrLoadIfAvailable(rider.getId());
        if (loaded == 0) {
            log.warn("[派单] 骑手负载已满，回滚事务 riderId={}", rider.getId());
            throw new RuntimeException("骑手负载已满");  // 触发回滚 bindRiderIfPending
        }
        // 3. WebSocket 推送骑手 App
        Map<String, Object> push = new HashMap<>();
        push.put("type", "ASSIGN");
        push.put("deliveryId", deliveryId);
        push.put("expireSeconds", 30);
        wsHandler.pushTo(rider.getId(), push);

        log.info("[派单] OK deliveryId={} → riderId={} ({})",
                deliveryId, rider.getId(), rider.getRiderName());
        return true;
    }
}

