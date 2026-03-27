package sys.smc.coupon.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.enums.SeckillStatus;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.service.SeckillService;
import sys.smc.coupon.util.DynamicRateLimiterManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀监控指标采集（可选）
 * 
 * 功能：
 * 1. 采集限流速率、库存等指标
 * 2. 暴露给Prometheus
 * 3. 使用Grafana可视化
 * 
 * 优势 vs Sentinel Dashboard：
 * - 轻量级：只需添加micrometer依赖
 * - 通用：Prometheus是行业标准
 * - 灵活：可以接入现有监控体系
 * - 成本低：无需独立Dashboard服务
 * 
 * 使用：
 * 1. 添加依赖：micrometer-registry-prometheus
 * 2. 启用此组件
 * 3. 访问：http://localhost:8090/actuator/prometheus
 * 4. Grafana创建面板
 * 
 * @author System
 * @since 2026-03-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillMetricsCollector {

    private final MeterRegistry meterRegistry;
    private final SeckillService seckillService;
    private final SeckillActivityMapper activityMapper;
    private final DynamicRateLimiterManager rateLimiterManager;
    
    /**
     * 总请求数
     */
    private final AtomicLong totalRequests = new AtomicLong(0);
    
    /**
     * 被限流的请求数
     */
    private final AtomicLong blockedRequests = new AtomicLong(0);
    
    /**
     * 采集秒杀活动指标 - 每5秒一次
     */
    @Scheduled(fixedRate = 5000)
    public void collectActivityMetrics() {
        try {
            // 查询所有进行中的活动
            List<SeckillActivity> ongoingActivities = activityMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillActivity>()
                            .eq(SeckillActivity::getStatus, SeckillStatus.ONGOING.getCode())
                            .eq(SeckillActivity::getDeleted, 0)
            );
            
            for (SeckillActivity activity : ongoingActivities) {
                Long activityId = activity.getId();
                String activityName = activity.getName();
                
                // 1. 剩余库存
                int remainStock = seckillService.getRemainStock(activityId);
                meterRegistry.gauge("seckill.stock.remain", 
                        Tags.of("activity_id", activityId.toString(), 
                                "activity_name", activityName),
                        remainStock);
                
                // 2. 库存利用率
                int totalStock = activity.getTotalStock();
                double utilizationRate = totalStock > 0 
                        ? (double)(totalStock - remainStock) / totalStock * 100 
                        : 0;
                meterRegistry.gauge("seckill.stock.utilization_rate", 
                        Tags.of("activity_id", activityId.toString()),
                        utilizationRate);
                
                // 3. 当前限流速率
                double currentRate = rateLimiterManager.getCurrentRate(activityId);
                meterRegistry.gauge("seckill.rate_limiter.current_rate", 
                        Tags.of("activity_id", activityId.toString()),
                        currentRate);
                
                // 4. 用户已抢购人数（可选）
                // int grabbedUsers = seckillService.getGrabbedUsersCount(activityId);
                // meterRegistry.gauge("seckill.grabbed_users", grabbedUsers);
            }
            
            // 5. 进行中活动数量
            meterRegistry.gauge("seckill.ongoing_activities", ongoingActivities.size());
            
        } catch (Exception e) {
            log.error("采集秒杀指标失败", e);
        }
    }
    
    /**
     * 记录请求总数（被Controller或AOP调用）
     */
    public void recordRequest() {
        totalRequests.incrementAndGet();
    }
    
    /**
     * 记录被限流的请求（被Controller或AOP调用）
     */
    public void recordBlocked() {
        blockedRequests.incrementAndGet();
    }
    
    /**
     * 采集限流统计 - 每10秒一次
     */
    @Scheduled(fixedRate = 10000)
    public void collectRateLimitStats() {
        long total = totalRequests.getAndSet(0);
        long blocked = blockedRequests.getAndSet(0);
        
        if (total > 0) {
            // 限流率
            double blockRate = (double) blocked / total * 100;
            
            meterRegistry.gauge("seckill.rate_limit.total_requests", total);
            meterRegistry.gauge("seckill.rate_limit.blocked_requests", blocked);
            meterRegistry.gauge("seckill.rate_limit.block_rate", blockRate);
            
            log.info("限流统计: 总请求={}, 被限流={}, 限流率={:.2f}%", 
                    total, blocked, blockRate);
        }
    }
}

