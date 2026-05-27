package com.sc.supplychain.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 配置
 *
 * <pre>
 * sc.inventory.deduct          — 订单预扣，异步落库 lockedQty/availableQty
 * sc.inventory.restore         — 取消/超时归还库存
 * sc.inventory.confirm         — 出库确认，扣减 lockedQty/totalQty
 * sc.replenishment.trigger     — 触发补货单生成
 * sc.expiry.warning            — 临期预警推送
 * *.dlt                        — 各 Topic 对应死信队列
 * </pre>
 */
@Configuration
public class KafkaConfig {

    // ── Topic 名称常量 ────────────────────────────────────────────────
    public static final String TOPIC_INVENTORY_DEDUCT         = "sc.inventory.deduct";
    public static final String TOPIC_INVENTORY_RESTORE        = "sc.inventory.restore";
    public static final String TOPIC_INVENTORY_CONFIRM        = "sc.inventory.confirm";
    public static final String TOPIC_REPLENISHMENT_TRIGGER    = "sc.replenishment.trigger";
    public static final String TOPIC_EXPIRY_WARNING           = "sc.expiry.warning";

    // 死信 Topic
    public static final String TOPIC_INVENTORY_DEDUCT_DLT     = "sc.inventory.deduct.dlt";
    public static final String TOPIC_REPLENISHMENT_TRIGGER_DLT = "sc.replenishment.trigger.dlt";

    @Bean public NewTopic inventoryDeductTopic() {
        return TopicBuilder.name(TOPIC_INVENTORY_DEDUCT).partitions(10).replicas(1).build();
    }
    @Bean public NewTopic inventoryDeductDltTopic() {
        return TopicBuilder.name(TOPIC_INVENTORY_DEDUCT_DLT).partitions(3).replicas(1).build();
    }
    @Bean public NewTopic inventoryRestoreTopic() {
        return TopicBuilder.name(TOPIC_INVENTORY_RESTORE).partitions(5).replicas(1).build();
    }
    @Bean public NewTopic inventoryConfirmTopic() {
        return TopicBuilder.name(TOPIC_INVENTORY_CONFIRM).partitions(5).replicas(1).build();
    }
    @Bean public NewTopic replenishmentTriggerTopic() {
        return TopicBuilder.name(TOPIC_REPLENISHMENT_TRIGGER).partitions(3).replicas(1).build();
    }
    @Bean public NewTopic replenishmentTriggerDltTopic() {
        return TopicBuilder.name(TOPIC_REPLENISHMENT_TRIGGER_DLT).partitions(1).replicas(1).build();
    }
    @Bean public NewTopic expiryWarningTopic() {
        return TopicBuilder.name(TOPIC_EXPIRY_WARNING).partitions(3).replicas(1).build();
    }
}

