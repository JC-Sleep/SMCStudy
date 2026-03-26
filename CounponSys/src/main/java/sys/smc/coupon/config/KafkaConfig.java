package sys.smc.coupon.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka配置类
 * 高并发场景推荐使用Kafka (支持10W+ QPS)
 */
@Configuration
public class KafkaConfig {

    /**
     * Topic名称常量
     */
    public static final String TOPIC_SECKILL_ORDER = "coupon.seckill.order";
    public static final String TOPIC_COUPON_GRANT = "coupon.grant";
    public static final String TOPIC_CONSUMPTION_EVENT = "coupon.consumption.event";
    public static final String TOPIC_COUPON_EXPIRE = "coupon.expire";

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

    /**
     * 优惠券发放Topic
     */
    @Bean
    public NewTopic couponGrantTopic() {
        return TopicBuilder.name(TOPIC_COUPON_GRANT)
                .partitions(5)
                .replicas(2)
                .build();
    }

    /**
     * 消费事件Topic (对接计费系统)
     */
    @Bean
    public NewTopic consumptionEventTopic() {
        return TopicBuilder.name(TOPIC_CONSUMPTION_EVENT)
                .partitions(5)
                .replicas(2)
                .build();
    }

    /**
     * 优惠券过期Topic
     */
    @Bean
    public NewTopic couponExpireTopic() {
        return TopicBuilder.name(TOPIC_COUPON_EXPIRE)
                .partitions(3)
                .replicas(2)
                .build();
    }
}

