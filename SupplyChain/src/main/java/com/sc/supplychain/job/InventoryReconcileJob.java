package com.sc.supplychain.job;

import com.sc.supplychain.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 库存对账定时任务
 * 每天凌晨2点全量比对 Redis available 与 DB available_qty
 * 差值超过阈值则：告警 + 以 DB 为准修复 Redis
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReconcileJob {

    private final InventoryService inventoryService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void run() {
        log.info("[InventoryReconcileJob] 开始库存对账...");
        try {
            inventoryService.reconcile();
            log.info("[InventoryReconcileJob] 对账完成");
        } catch (Exception e) {
            log.error("[InventoryReconcileJob] 对账异常", e);
        }
    }
}
