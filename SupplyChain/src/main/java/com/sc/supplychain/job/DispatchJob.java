package com.sc.supplychain.job;

import com.sc.supplychain.entity.DeliveryOrder;
import com.sc.supplychain.mapper.DeliveryOrderMapper;
import com.sc.supplychain.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 派单任务：每 5 秒扫描 PENDING 配送单，尝试智能派单 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchJob {

    private final DeliveryOrderMapper deliveryMapper;
    private final DispatchService dispatchService;

    /** TODO Phase 2.2 多仓多副本要加 ShedLock */
    @Scheduled(fixedDelay = 5000)
    public void run() {
        // 简化：扫描所有仓库的 PENDING（生产应循环 warehouseId 列表）
        List<DeliveryOrder> pendings = deliveryMapper.selectPendingByWarehouse(1L);
        if (pendings.isEmpty()) return;
        log.debug("[DispatchJob] 待派单 {} 条", pendings.size());
        for (DeliveryOrder d : pendings) {
            try {
                boolean ok = dispatchService.dispatch(d.getId());
                if (!ok) {
                    // TODO 转入抢单池广播（Phase 2.2）
                    log.debug("[DispatchJob] 暂无可用骑手，留待下次 deliveryId={}", d.getId());
                }
            } catch (Exception e) {
                log.warn("[DispatchJob] 派单异常 deliveryId={} err={}", d.getId(), e.getMessage());
            }
        }
    }
}

