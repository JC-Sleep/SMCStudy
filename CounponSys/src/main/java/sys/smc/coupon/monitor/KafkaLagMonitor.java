package sys.smc.coupon.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
    
    /**
     * 定时检查Kafka积压
     * 
     * 执行频率：每30秒一次
     */
    @Scheduled(fixedRate = 30000)
    public void checkLag() {
        try {
            // 获取消费者积压数量
            long totalLag = getConsumerLag(CONSUMER_GROUP_ID);
            
            log.info("Kafka消费者积压检查: group={}, lag={}", CONSUMER_GROUP_ID, totalLag);
            
            // 判断是否需要告警
            if (totalLag > CRITICAL_THRESHOLD) {
                // 严重告警
                sendCriticalAlert(totalLag);
            } else if (totalLag > WARNING_THRESHOLD) {
                // 普通告警
                sendWarningAlert(totalLag);
            }
            
        } catch (Exception e) {
            log.error("Kafka积压检查失败", e);
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
     * @param lag 积压数量
     */
    private void sendWarningAlert(long lag) {
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
                "积压数量: %d 条\n" +
                "告警级别: 普通\n" +
                "建议: 关注消费速度，如持续增长需要优化",
                CONSUMER_GROUP_ID,
                KafkaConfig.TOPIC_SECKILL_ORDER,
                lag
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
    private void sendCriticalAlert(long lag) {
        // 严重告警不受频率限制
        
        String message = String.format(
                "【Kafka严重积压告警】\n" +
                "消费者组: %s\n" +
                "Topic: %s\n" +
                "积压数量: %d 条\n" +
                "告警级别: 严重 ⚠️⚠️⚠️\n" +
                "\n" +
                "处理建议:\n" +
                "1. 立即增加消费者实例数\n" +
                "2. 临时提升concurrency配置(当前20，可提升到30)\n" +
                "3. 检查消费者是否有异常\n" +
                "4. 检查数据库/积分服务是否正常\n" +
                "\n" +
                "预计消费完成时间: %.1f 分钟 (按当前速度)",
                CONSUMER_GROUP_ID,
                KafkaConfig.TOPIC_SECKILL_ORDER,
                lag,
                lag / 1000.0 / 20 / 60  // 假设20个消费者，每个每秒处理1000条
        );
        
        // TODO: 调用告警服务发送紧急通知（电话+短信+邮件+钉钉）
        // alertService.sendUrgent(message);
        
        log.error("Kafka严重积压告警: {}", message);
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

