package sys.smc.payment.config;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guava RateLimiter 限流配置
 *
 * ⑩ 修复：防止突发流量打穿数据库连接池
 *
 * 注意：Guava RateLimiter 是单实例级别的限流
 *   3个实例 × 100 QPS = 总计放行 300 QPS（符合系统上限）
 *   如果换微服务，改用 Sentinel 集群限流，保证全局 100 QPS 总量
 *
 * QPS 设置依据（基于 application.yml 计算）：
 *   支付发起：银行API限制约100 QPS/实例
 *   回调处理：DB 60连接限制约333 QPS（3实例共享，每实例约111）
 *   查询状态：DB 60连接支持 6000 QPS，500/实例绰绰有余
 */
@Slf4j
@Configuration
public class RateLimiterConfig {

    @Value("${payment.rate-limiter.payment-qps:100}")
    private double paymentQps;

    @Value("${payment.rate-limiter.callback-qps:300}")
    private double callbackQps;

    @Value("${payment.rate-limiter.query-qps:500}")
    private double queryQps;

    /**
     * 支付发起限流（令牌桶）
     * 默认 100 QPS/实例，通过 PAYMENT_RATE_QPS 环境变量覆盖
     */
    @Bean("paymentRateLimiter")
    public RateLimiter paymentRateLimiter() {
        log.info("支付发起限流初始化：{} QPS/实例", paymentQps);
        return RateLimiter.create(paymentQps);
    }

    /**
     * 回调处理限流（令牌桶）
     * 默认 300 QPS/实例
     */
    @Bean("callbackRateLimiter")
    public RateLimiter callbackRateLimiter() {
        log.info("回调处理限流初始化：{} QPS/实例", callbackQps);
        return RateLimiter.create(callbackQps);
    }

    /**
     * 状态查询限流（令牌桶）
     * 默认 500 QPS/实例
     */
    @Bean("queryRateLimiter")
    public RateLimiter queryRateLimiter() {
        log.info("状态查询限流初始化：{} QPS/实例", queryQps);
        return RateLimiter.create(queryQps);
    }
}

