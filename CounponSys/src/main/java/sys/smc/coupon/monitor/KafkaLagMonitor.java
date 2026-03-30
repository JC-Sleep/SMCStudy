package sys.smc.coupon.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sys.smc.coupon.config.KafkaConfig;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Kafka消息积压监控
 * 
 * 功能：
 * 1. 定期检查Kafka消费者积压情况
 * 2. 积压超过阈值时触发告警
 * 3. 提供积压趋势分析
 * 
 * 告警策略：
 * - 积压 > 1万条：普通告警
 * - 积压 > 10万条：严重告警（需要人工介入）
 * 
 * 处理建议：
 * - 短期：增加消费者实例数
 * - 短期：临时提升concurrency配置
 * - 长期：优化消费者处理逻辑
 * 
 * @author System
 * @since 2026-03-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaLagMonitor {

    private final KafkaAdmin kafkaAdmin;
    
    // 2026-03-27 新增：支持Prometheus指标暴露
    @Autowired(required = false)
    private MeterRegistry meterRegistry;
    
    /**
     * 秒杀订单消费者组ID
     */
    private static final String CONSUMER_GROUP_ID = "seckill-order-consumer";
    
    /**
     * 普通告警阈值：1万条
     */
    private static final long WARNING_THRESHOLD = 10000;
    
    /**
     * 严重告警阈值：10万条
     */
    private static final long CRITICAL_THRESHOLD = 100000;
    
    /**
     * 上次告警时间（防止频繁告警）
     */
    private long lastAlertTime = 0;
    
    /**
     * 告警间隔：5分钟
     */
    private static final long ALERT_INTERVAL = 5 * 60 * 1000;
    
    // 2026-03-27 新增：记录上次offset，用于计算消费速度
    private long lastOffset = 0;
    private long lastCheckTime = System.currentTimeMillis();
    
    /**
     * 定时检查Kafka积压（增强版）
     * 
     * 执行频率：每15秒一次（优化：从30秒改为15秒，更快响应）
     * 
     * 2026-03-27 增强：
     * 1. 暴露指标给Prometheus（支持HPA/KEDA自动扩缩容）
     * 2. 计算消费速度和预计恢复时间
     * 3. 告警附带自动化处理命令
     */
    @Scheduled(fixedRate = 15000)  // 2026-03-27 优化：改为15秒
    public void checkLag() {
        try {
            // 1. 获取消费者积压数量
            long totalLag = getConsumerLag(CONSUMER_GROUP_ID);
            
            // 2. 计算消费速度
            long currentTime = System.currentTimeMillis();
            long currentOffset = getCurrentTotalOffset();
            long timeDiff = (currentTime - lastCheckTime) / 1000;  // 秒
            long consumeSpeed = timeDiff > 0 ? (currentOffset - lastOffset) / timeDiff : 0;
            
            // 3. 预估恢复时间
            long estimatedSeconds = consumeSpeed > 0 ? totalLag / consumeSpeed : -1;
            long estimatedMinutes = estimatedSeconds > 0 ? estimatedSeconds / 60 : 0;
            
            // 4. 暴露指标给Prometheus（支持HPA/KEDA）
            if (meterRegistry != null) {
                meterRegistry.gauge("kafka.consumer.lag", 
                    Tags.of("group", CONSUMER_GROUP_ID, "topic", KafkaConfig.TOPIC_SECKILL_ORDER), 
                    totalLag);
                meterRegistry.gauge("kafka.consumer.speed", 
                    Tags.of("group", CONSUMER_GROUP_ID), 
                    consumeSpeed);
                if (estimatedSeconds > 0) {
                    meterRegistry.gauge("kafka.estimated.recovery.minutes", 
                        Tags.of("group", CONSUMER_GROUP_ID), 
                        estimatedMinutes);
                }
            }
            
            // 5. 记录详细日志
            log.info("Kafka监控: group={}, lag={}, speed={}/s, 预计恢复={}分钟", 
                    CONSUMER_GROUP_ID, totalLag, consumeSpeed, 
                    estimatedSeconds > 0 ? estimatedMinutes : "计算中");
            
            // 6. 判断是否需要告警
            if (totalLag > CRITICAL_THRESHOLD) {
                sendCriticalAlert(totalLag, estimatedMinutes, consumeSpeed);
            } else if (totalLag > WARNING_THRESHOLD) {
                sendWarningAlert(totalLag, estimatedMinutes);
            }
            
            // 7. 更新上次检查信息
            lastOffset = currentOffset;
            lastCheckTime = currentTime;
            
        } catch (Exception e) {
            log.error("Kafka积压检查失败", e);
        }
    }
    
    /**
     * 获取当前总offset（用于计算消费速度）
     * 
     * 2026-03-27 新增
     */
    private long getCurrentTotalOffset() {
        try {
            long total = 0;
            try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                Map<TopicPartition, OffsetAndMetadata> offsets = adminClient
                        .listConsumerGroupOffsets(CONSUMER_GROUP_ID)
                        .partitionsToOffsetAndMetadata()
                        .get();
                
                for (OffsetAndMetadata offset : offsets.values()) {
                    total += offset.offset();
                }
            }
            return total;
        } catch (Exception e) {
            log.debug("获取总offset失败", e);
            return 0;
        }
    }
    
    /**
     * 获取消费者积压数量
     * 
     * 计算逻辑：
     * lag = 生产者offset - 消费者offset
     * 
     * @param groupId 消费者组ID
     * @return 总积压数量
     */
    public long getConsumerLag(String groupId) throws ExecutionException, InterruptedException {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            
            // 1. 获取消费者组详情
            ConsumerGroupDescription groupDesc = adminClient
                    .describeConsumerGroups(Collections.singletonList(groupId))
                    .all()
                    .get()
                    .get(groupId);
            
            // 2. 获取消费者已提交的offset
            Map<TopicPartition, OffsetAndMetadata> committedOffsets = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get();
            
            // 3. 获取每个分区的最新offset
            Set<TopicPartition> topicPartitions = committedOffsets.keySet();
            Map<TopicPartition, Long> endOffsets = adminClient
                    .listOffsets(createListOffsetsMap(topicPartitions))
                    .all()
                    .get()
                    .entrySet()
                    .stream()
                    .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue().offset()), HashMap::putAll);
            
            // 4. 计算总积压
            long totalLag = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committedOffsets.entrySet()) {
                TopicPartition partition = entry.getKey();
                long committedOffset = entry.getValue().offset();
                Long endOffset = endOffsets.get(partition);
                
                if (endOffset != null) {
                    long lag = endOffset - committedOffset;
                    totalLag += Math.max(lag, 0);
                    
                    if (lag > 1000) {
                        log.debug("分区积压: partition={}, lag={}", partition, lag);
                    }
                }
            }
            
            return totalLag;
        }
    }
    
    /**
     * 创建ListOffsets请求参数
     */
    private Map<TopicPartition, org.apache.kafka.clients.admin.OffsetSpec> createListOffsetsMap(Set<TopicPartition> partitions) {
        Map<TopicPartition, org.apache.kafka.clients.admin.OffsetSpec> map = new HashMap<>();
        for (TopicPartition partition : partitions) {
            map.put(partition, org.apache.kafka.clients.admin.OffsetSpec.latest());
        }
        return map;
    }
    
    /**
     * 发送普通告警
     * 
     * 2026-03-27 优化：添加预计恢复时间
     * 
     * @param lag 积压数量
     * @param estimatedMinutes 预计恢复时间（分钟）
     */
    private void sendWarningAlert(long lag, long estimatedMinutes) {
        // 防止频繁告警
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAlertTime < ALERT_INTERVAL) {
            return;
        }
        lastAlertTime = currentTime;
        
        String message = String.format(
                "【Kafka积压告警】\n" +
                "消费者组: %s\n" +
                "Topic: %s\n" +
                "积压数量: %,d 条\n" +
                "预计恢复: %d 分钟\n" +
                "告警级别: 普通\n" +
                "建议: 关注消费速度，如持续增长需要扩容",
                CONSUMER_GROUP_ID,
                KafkaConfig.TOPIC_SECKILL_ORDER,
                lag,
                estimatedMinutes
        );
        
        // TODO: 调用告警服务发送通知（短信/邮件/钉钉等）
        // alertService.send(message);
        
        log.warn("Kafka积压告警: {}", message);
    }
    
    /**
     * 发送严重告警
     * 
     * @param lag 积压数量
     */
    private void sendCriticalAlert(long lag, long estimatedMinutes, long consumeSpeed) {
        // 严重告警不受频率限制
        
        // 2026-03-27 优化：计算需要的实例数
        int currentReplicas = 2;  // 当前实例数（从配置或API获取）
        int recommendedReplicas = (int) Math.min(20, Math.ceil((double) lag / 5000));  // 每个实例处理5000条
        
        String message = String.format(
                "【Kafka严重积压告警】⚠️⚠️⚠️\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "消费者组: %s\n" +
                "Topic: %s\n" +
                "积压数量: %,d 条\n" +
                "消费速度: %,d 条/秒\n" +
                "预计恢复: %d 分钟\n" +
                "告警级别: 严重 ⚠️⚠️⚠️\n" +
                "\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "自动化处理建议：\n" +
                "\n" +
                "方案1：KEDA自动扩缩容（推荐）\n" +
                "  如已部署KEDA，会自动扩容到%d个实例\n" +
                "  无需人工介入 ✅\n" +
                "\n" +
                "方案2：手动扩容（临时方案）\n" +
                "  kubectl scale deployment coupon-system --replicas=%d\n" +
                "\n" +
                "方案3：执行快速扩容脚本\n" +
                "  ./scripts/scale-up.sh\n" +
                "\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "扩容后预计：\n" +
                "  消费速度: %,d 条/秒 → %,d 条/秒\n" +
                "  恢复时间: %d 分钟 → %.1f 分钟\n" +
                "\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "监控地址:\n" +
                "  Grafana: http://grafana/d/kafka-lag\n" +
                "  Prometheus: http://prometheus/graph\n" +
                "\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━",
                CONSUMER_GROUP_ID,
                KafkaConfig.TOPIC_SECKILL_ORDER,
                lag,
                consumeSpeed,
                estimatedMinutes,
                recommendedReplicas,
                recommendedReplicas,
                consumeSpeed,
                consumeSpeed * recommendedReplicas / currentReplicas,
                estimatedMinutes,
                (double) lag / (consumeSpeed * recommendedReplicas / currentReplicas) / 60
        );
        
        // TODO: 调用告警服务发送紧急通知（电话+短信+邮件+钉钉）
        // alertService.sendUrgent(message);
        
        log.error("Kafka严重积压告警:\n{}", message);
    }
    
    /**
     * 获取消费者健康状态（用于监控面板）
     * 
     * @return 健康状态信息
     */
    public ConsumerHealthStatus getHealthStatus() {
        try {
            long lag = getConsumerLag(CONSUMER_GROUP_ID);
            
            String status;
            String level;
            if (lag > CRITICAL_THRESHOLD) {
                status = "严重积压";
                level = "CRITICAL";
            } else if (lag > WARNING_THRESHOLD) {
                status = "积压中";
                level = "WARNING";
            } else {
                status = "正常";
                level = "OK";
            }
            
            return new ConsumerHealthStatus(
                    CONSUMER_GROUP_ID,
                    lag,
                    status,
                    level,
                    System.currentTimeMillis()
            );
            
        } catch (Exception e) {
            log.error("获取消费者健康状态失败", e);
            return new ConsumerHealthStatus(
                    CONSUMER_GROUP_ID,
                    -1,
                    "检查失败",
                    "ERROR",
                    System.currentTimeMillis()
            );
        }
    }
    
    /**
     * 消费者健康状态
     */
    public static class ConsumerHealthStatus {
        public final String groupId;
        public final long lag;
        public final String status;
        public final String level;
        public final long timestamp;
        
        public ConsumerHealthStatus(String groupId, long lag, String status, String level, long timestamp) {
            this.groupId = groupId;
            this.lag = lag;
            this.status = status;
            this.level = level;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("ConsumerHealthStatus{groupId='%s', lag=%d, status='%s', level='%s'}", 
                    groupId, lag, status, level);
        }
    }
}

