package sys.smc.coupon.config;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流配置
 * 使用Guava RateLimiter实现令牌桶限流
 */
@Configuration
public class RateLimiterConfig {

    @Value("${seckill.rate-limit.capacity:10000}")
    private int capacity;

    /**
     * 全局限流器 - 秒杀接口
     * 每秒允许10000个请求
     */
    @Bean
    public RateLimiter seckillRateLimiter() {
        return RateLimiter.create(capacity);
    }

    /**
     * 活动级别限流器缓存
     * Key: activityId
     * Value: RateLimiter
     */
    @Bean
    public Map<Long, RateLimiter> activityRateLimiters() {
        return new ConcurrentHashMap<>();
    }
}

