package sys.smc.coupon.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka配置类（2026-04-22 新增死信Topic）
 *
 * Topic设计：
 *   coupon.seckill.order     — 正常秒杀订单（10分区，高并发）
 *   coupon.seckill.order.dlt — 死信队列（消费重试超限后进入，人工补偿）
 *
 * 重试机制（不用额外重试Topic）：
 *   Kafka不原生支持延迟消息，本系统用 T_SECKILL_RETRY_TASK 表 + 定时Job
 *   实现指数退避重试，与 T_GRANT_TASK 模式一致，简单可靠
 */
@Configuration
public class KafkaConfig {

    // ── 正常业务 Topic ──
    public static final String TOPIC_SECKILL_ORDER     = "coupon.seckill.order";
    public static final String TOPIC_COUPON_GRANT      = "coupon.grant";
    public static final String TOPIC_CONSUMPTION_EVENT = "coupon.consumption.event";
    public static final String TOPIC_COUPON_EXPIRE     = "coupon.expire";

    /**
     * 死信 Topic（2026-04-22 新增）
     * 触发条件：秒杀订单消费重试3次仍失败后，消息进入此Topic
     * 死信消费者负责：持久化最终失败状态 + 打印人工告警日志
     */
    public static final String TOPIC_SECKILL_ORDER_DLT = "coupon.seckill.order.dlt";

    /**
     * 秒杀订单Topic - 高并发核心
     * 分区数=10，副本数=2，保证高吞吐和可靠性
     */
    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(TOPIC_SECKILL_ORDER)
                .partitions(10)  // 10个分区，支持10个消费者并行处理
                .replicas(2)     // 2个副本，保证高可用
                .build();
    }

    /** 死信Topic：3分区，低流量，高可靠 */
    @Bean
    public NewTopic seckillOrderDltTopic() {
        return TopicBuilder.name(TOPIC_SECKILL_ORDER_DLT)
                .partitions(3).replicas(2).build();
    }

    /**
     * 优惠券发放Topic
     */
    @Bean
    public NewTopic couponGrantTopic() {
        return TopicBuilder.name(TOPIC_COUPON_GRANT)
                .partitions(5).replicas(2).build();
    }

    /**
     * 消费事件Topic (对接计费系统)
     */
    @Bean
    public NewTopic consumptionEventTopic() {
        return TopicBuilder.name(TOPIC_CONSUMPTION_EVENT)
                .partitions(5).replicas(2).build();
    }

    /**
     * 优惠券过期Topic
     */
    @Bean
    public NewTopic couponExpireTopic() {
        return TopicBuilder.name(TOPIC_COUPON_EXPIRE)
                .partitions(3).replicas(2).build();
    }
}
