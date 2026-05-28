package com.sc.supplychain.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 金蝶云数据同步定时任务（Phase2预留外壳）
 * 每天凌晨1点将出库/入库/结算数据推金蝶
 *
 * TODO Phase2: 注入 KingdeeApiClient，实现三种凭证推送
 *   - 出库单 → 销售凭证
 *   - 采购入库 → 应付凭证
 *   - 出库成本 → 成本凭证
 */
@Slf4j
@Component
public class KingdeeDataSyncJob {

    @Scheduled(cron = "0 0 1 * * ?")
    public void run() {
        log.info("[KingdeeDataSyncJob] Phase2预留 - 金蝶同步待实现");
        // TODO Phase2: implement sync logic
    }
}
