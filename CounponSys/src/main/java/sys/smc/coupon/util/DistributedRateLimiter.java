package sys.smc.coupon.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Collections;

/**
 * 分布式限流器
 * 基于Redis + Lua实现，支持多实例部署
 * 
 * 与Guava RateLimiter的区别:
 * - Guava: 单机限流，每个实例独立计数
 * - 本类: 分布式限流，所有实例共享计数
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 滑动窗口限流Lua脚本
     * 
     * 原理:
     * 1. 获取当前计数
     * 2. 如果超过限制，返回0(拒绝)
     * 3. 否则计数+1，设置过期时间，返回1(放行)
     */
    private static final String SLIDING_WINDOW_SCRIPT =
            "local key = KEYS[1] " +
            "local limit = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local current = tonumber(redis.call('GET', key) or '0') " +
            "if current >= limit then " +
            "    return 0 " +
            "end " +
            "current = redis.call('INCR', key) " +
            "if current == 1 then " +
            "    redis.call('EXPIRE', key, window) " +
            "end " +
            "return 1";

    /**
     * 令牌桶限流Lua脚本
     * 
     * 原理:
     * 1. 计算自上次请求后应该补充的令牌数
     * 2. 令牌数不能超过桶容量
     * 3. 如果令牌>=1，消耗一个令牌，放行
     * 4. 否则拒绝
     */
    private static final String TOKEN_BUCKET_SCRIPT =
            "local key = KEYS[1] " +
            "local capacity = tonumber(ARGV[1]) " +      // 桶容量
            "local rate = tonumber(ARGV[2]) " +          // 每秒生成令牌数
            "local now = tonumber(ARGV[3]) " +           // 当前时间戳(毫秒)
            "local requested = tonumber(ARGV[4]) " +     // 请求的令牌数
            "" +
            "local data = redis.call('HMGET', key, 'tokens', 'timestamp') " +
            "local tokens = tonumber(data[1]) " +
            "local lastTime = tonumber(data[2]) " +
            "" +
            "if tokens == nil then " +
            "    tokens = capacity " +
            "    lastTime = now " +
            "end " +
            "" +
            "local elapsed = (now - lastTime) / 1000.0 " +
            "local newTokens = math.min(capacity, tokens + elapsed * rate) " +
            "" +
            "if newTokens >= requested then " +
            "    redis.call('HMSET', key, 'tokens', newTokens - requested, 'timestamp', now) " +
            "    redis.call('EXPIRE', key, 60) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    private DefaultRedisScript<Long> slidingWindowScript;
    private DefaultRedisScript<Long> tokenBucketScript;

    @PostConstruct
    public void init() {
        slidingWindowScript = new DefaultRedisScript<>(SLIDING_WINDOW_SCRIPT, Long.class);
        tokenBucketScript = new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, Long.class);
    }

    /**
     * 滑动窗口限流
     * 
     * @param key 限流key
     * @param limit 时间窗口内允许的请求数
     * @param windowSeconds 时间窗口(秒)
     * @return true=放行, false=拒绝
     * 
     * 示例:
     * tryAcquire("api:seckill:grab", 10000, 1) - 每秒最多10000次
     * tryAcquire("user:U123", 10, 1) - 每个用户每秒最多10次
     * tryAcquire("ip:1.2.3.4", 100, 60) - 每个IP每分钟最多100次
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        try {
            Long result = redisTemplate.execute(
                    slidingWindowScript,
                    Collections.singletonList(key),
                    limit, windowSeconds
            );
            return result != null && result == 1;
        } catch (Exception e) {
            log.error("分布式限流异常: key={}", key, e);
            // 限流器故障时，默认放行(降级策略)
            return true;
        }
    }

    /**
     * 令牌桶限流
     * 
     * @param key 限流key
     * @param capacity 桶容量(最大突发量)
     * @param rate 每秒生成令牌数(平均速率)
     * @return true=放行, false=拒绝
     * 
     * 示例:
     * tryAcquireToken("api:seckill", 1000, 100) 
     * - 桶容量1000，每秒补充100个
     * - 允许突发1000个请求，长期平均100/秒
     */
    public boolean tryAcquireToken(String key, int capacity, int rate) {
        try {
            Long result = redisTemplate.execute(
                    tokenBucketScript,
                    Collections.singletonList(key),
                    capacity, rate, System.currentTimeMillis(), 1
            );
            return result != null && result == 1;
        } catch (Exception e) {
            log.error("令牌桶限流异常: key={}", key, e);
            return true;
        }
    }

    /**
     * 获取剩余配额
     * 
     * @param key 限流key
     * @param limit 限制数
     * @return 剩余可用次数
     */
    public int getRemaining(String key, int limit) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return limit;
            }
            int used = Integer.parseInt(value.toString());
            return Math.max(0, limit - used);
        } catch (Exception e) {
            log.error("获取限流剩余配额异常: key={}", key, e);
            return limit;
        }
    }
}

