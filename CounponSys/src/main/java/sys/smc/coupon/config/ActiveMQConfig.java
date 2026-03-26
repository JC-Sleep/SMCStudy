package sys.smc.coupon.config;

import org.apache.activemq.command.ActiveMQQueue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;

import javax.jms.ConnectionFactory;
import javax.jms.Queue;

/**
 * ActiveMQ配置类
 */
@Configuration
@EnableJms
public class ActiveMQConfig {

    /**
     * 优惠券发放队列
     */
    public static final String COUPON_GRANT_QUEUE = "coupon.grant.queue";

    /**
     * 秒杀订单处理队列
     */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    /**
     * 优惠券过期处理队列
     */
    public static final String COUPON_EXPIRE_QUEUE = "coupon.expire.queue";

    /**
     * 消费事件队列(对接计费系统)
     */
    public static final String CONSUMPTION_EVENT_QUEUE = "consumption.event.queue";

    @Bean
    public Queue couponGrantQueue() {
        return new ActiveMQQueue(COUPON_GRANT_QUEUE);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return new ActiveMQQueue(SECKILL_ORDER_QUEUE);
    }

    @Bean
    public Queue couponExpireQueue() {
        return new ActiveMQQueue(COUPON_EXPIRE_QUEUE);
    }

    @Bean
    public Queue consumptionEventQueue() {
        return new ActiveMQQueue(CONSUMPTION_EVENT_QUEUE);
    }

    /**
     * 配置JMS监听器容器工厂
     */
    @Bean
    public JmsListenerContainerFactory<?> jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency("5-10"); // 5-10个并发消费者
        factory.setSessionTransacted(true); // 开启事务
        return factory;
    }
}

