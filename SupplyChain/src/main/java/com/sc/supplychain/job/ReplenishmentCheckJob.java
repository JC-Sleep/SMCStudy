package com.sc.supplychain.job;

import com.sc.supplychain.service.ReplenishmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动补货扫描定时任务
 * 每30分钟扫描一次所有启用的补货规则
 * 可用库存 < 最小库存阈值 → 发 Kafka sc.replenishment.trigger
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplenishmentCheckJob {

    private final ReplenishmentService replenishmentService;

    @Scheduled(cron = "0 */30 * * * ?")
    public void run() {
        log.info("[ReplenishmentCheckJob] 开始补货扫描...");
        try {
            replenishmentService.checkAndTrigger();
        } catch (Exception e) {
            log.error("[ReplenishmentCheckJob] 执行异常", e);
        }
    }
}
