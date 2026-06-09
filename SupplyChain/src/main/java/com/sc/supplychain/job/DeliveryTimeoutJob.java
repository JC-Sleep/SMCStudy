package com.sc.supplychain.job;

import com.sc.supplychain.entity.DeliveryOrder;
import com.sc.supplychain.enums.DeliveryStatus;
import com.sc.supplychain.mapper.DeliveryOrderMapper;
import com.sc.supplychain.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 超时扫描：取货超时自动改派；送达超时打 TIMEOUT 标志（不阻断仍可继续送达）*/
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryTimeoutJob {

    private final DeliveryOrderMapper deliveryMapper;
    private final DeliveryService deliveryService;

    @Scheduled(fixedDelay = 60_000)  // 每分钟
    public void scan() {
        // 1. 取货超时 → 改派
        List<DeliveryOrder> pickupExp = deliveryMapper.selectPickupTimeout();
        for (DeliveryOrder d : pickupExp) {
            try {
                deliveryService.reassign(d.getId());
                log.warn("[超时-取货] 自动改派 deliveryId={}", d.getId());
            } catch (Exception e) {
                log.error("[超时-取货] 改派失败 deliveryId={} err={}", d.getId(), e.getMessage());
            }
        }
        // 2. 送达超时 → 标 TIMEOUT（不停止配送，仅打标 + 触发告警）
        List<DeliveryOrder> deliverExp = deliveryMapper.selectDeliverTimeout();
        for (DeliveryOrder d : deliverExp) {
            int aff = deliveryMapper.transition(d.getId(), d.getStatus(),
                    DeliveryStatus.TIMEOUT.getCode());
            if (aff > 0) {
                log.warn("[超时-送达] deliveryId={} status被打标TIMEOUT，触发告警", d.getId());
                // TODO 推送客服告警
            }
        }
    }
}

