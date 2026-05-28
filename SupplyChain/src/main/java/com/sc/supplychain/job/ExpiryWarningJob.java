package com.sc.supplychain.job;

import com.sc.supplychain.config.KafkaConfig;
import com.sc.supplychain.config.SupplyChainProperties;
import com.sc.supplychain.entity.ExpiryWarning;
import com.sc.supplychain.entity.InventoryBatch;
import com.sc.supplychain.enums.BatchStatus;
import com.sc.supplychain.mapper.ExpiryWarningMapper;
import com.sc.supplychain.mapper.InventoryBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 生鲜效期预警定时任务
 * 每天早8点扫描，判断 inbound_batch 中临期批次
 *
 * 分级标准（从 application.yml 读取，可按品类配置）:
 *   ≤ warnDaysNear  → NEAR_EXPIRY（黄色，发预警）
 *   ≤ warnDaysUrgent → URGENT（红色，发预警+告警）
 *   < today          → EXPIRED（下架，记录）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryWarningJob {

    private final ExpiryWarningMapper warningMapper;
    private final InventoryBatchMapper batchMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SupplyChainProperties properties;

    @Scheduled(cron = "0 0 8 * * ?")
    public void run() {
        log.info("[ExpiryWarningJob] 开始效期预警扫描...");
        int warnDaysNear   = properties.getExpiry().getWarnDaysNear();
        int warnDaysUrgent = properties.getExpiry().getWarnDaysUrgent();
        LocalDate today = LocalDate.now();

        List<InventoryBatch> batches = warningMapper.selectNearExpiryBatches(warnDaysNear);
        int nearCount = 0, urgentCount = 0, expiredCount = 0;

        for (InventoryBatch batch : batches) {
            if (batch.getExpireDate() == null) continue;
            long daysLeft = ChronoUnit.DAYS.between(today, batch.getExpireDate());

            String newStatus;
            String warnLevel;

            if (daysLeft < 0) {
                newStatus = BatchStatus.EXPIRED.getCode();
                warnLevel = null; // 过期不写预警，直接更新批次状态
                expiredCount++;
            } else if (daysLeft <= warnDaysUrgent) {
                newStatus = BatchStatus.URGENT.getCode();
                warnLevel = "URGENT";
                urgentCount++;
            } else {
                newStatus = BatchStatus.NEAR_EXPIRY.getCode();
                warnLevel = "NEAR_EXPIRY";
                nearCount++;
            }

            // 更新批次状态
            if (!newStatus.equals(batch.getStatus())) {
                batch.setStatus(newStatus);
                batchMapper.updateById(batch);
            }

            // 写预警记录（仅 NEAR_EXPIRY / URGENT）
            if (warnLevel != null) {
                ExpiryWarning warning = new ExpiryWarning();
                warning.setBatchNo(batch.getBatchNo());
                warning.setSkuId(batch.getSkuId());
                warning.setWarnLevel(warnLevel);
                warning.setExpireDate(batch.getExpireDate());
                warning.setRemainQty(batch.getRemainQty());
                warning.setIsHandled(0);
                warningMapper.insert(warning);

                // 发 Kafka sc.expiry.warning（供下游推送短信/微信）
                kafkaTemplate.send(KafkaConfig.TOPIC_EXPIRY_WARNING,
                        batch.getBatchNo(), warning);
            }
        }
        log.info("[ExpiryWarningJob] 扫描完成：NearExpiry={} Urgent={} Expired={}",
                nearCount, urgentCount, expiredCount);
    }
}
