package sys.smc.payment.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShedLock 配置
 *
 * ① 修复：防止定时任务（对账Job）在多个实例上同时执行
 *
 * 原理：
 *   每次定时任务触发前，ShedLock 先在 Redis 中用 SET NX EX 抢占一个锁
 *   只有抢到锁的实例才执行任务，其他实例直接跳过
 *   锁自动在 lockAtMostFor 时间后释放（防止宕机后锁永久占用）
 *
 * 依赖（已在 pom.xml 添加）：
 *   shedlock-spring
 *   shedlock-provider-redis-spring
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M") // 默认最多持锁30分钟
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        // 使用 Redis 存储锁，key 前缀为 "shedlock:"
        return new RedisLockProvider(connectionFactory, "payment-system");
    }
}

