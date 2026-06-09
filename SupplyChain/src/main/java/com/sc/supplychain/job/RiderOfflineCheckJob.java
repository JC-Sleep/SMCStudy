package com.sc.supplychain.job;

import com.sc.supplychain.config.DeliveryProperties;
import com.sc.supplychain.mapper.RiderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 心跳超时下线：把超过阈值未心跳的骑手强制 OFFLINE */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiderOfflineCheckJob {

    private final RiderMapper riderMapper;
    private final DeliveryProperties properties;

    @Scheduled(fixedDelay = 60_000)
    public void check() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusSeconds(properties.getRiderOfflineThresholdSeconds());
        int n = riderMapper.offlineExpiredHeartbeat(threshold);
        if (n > 0) log.warn("[骑手-心跳超时] 强制下线 {} 个骑手", n);
    }
}

