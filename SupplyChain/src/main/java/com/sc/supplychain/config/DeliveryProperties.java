package com.sc.supplychain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 骑手配送相关配置 */
@Data
@Component
@ConfigurationProperties(prefix = "delivery")
public class DeliveryProperties {

    /** 抢单池过期秒数 */
    private int grabPoolExpireSeconds = 60;
    /** 接单后取货超时（分钟） */
    private int pickupTimeoutMinutes = 15;
    /** 取货后送达超时（分钟） */
    private int deliverTimeoutMinutes = 45;
    /** 最大改派次数 */
    private int reassignMaxCount = 3;
    /** 心跳间隔秒 */
    private int riderHeartbeatIntervalSeconds = 30;
    /** 心跳超过此秒数视为离线 */
    private int riderOfflineThresholdSeconds = 300;
    /** 抢单广播半径米 */
    private int broadcastRadiusMeters = 3000;

    /** 派单算法权重 */
    private double weightDistance = 0.5;
    private double weightLoad = 0.3;
    private double weightRating = 0.2;

    /** 计费 */
    private double baseFeeYuan = 5.0;
    private double distanceFeePerKm = 1.5;
    private double freeDistanceKm = 2.0;
    private double rushHourBonusYuan = 2.0;
    private double penaltyPerMinute = 0.5;
    private double penaltyCapYuan = 5.0;
}

