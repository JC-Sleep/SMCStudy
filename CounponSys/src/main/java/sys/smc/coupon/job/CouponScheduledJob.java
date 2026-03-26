package sys.smc.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.enums.SeckillStatus;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.service.CouponService;
import sys.smc.coupon.service.SeckillService;
// 2026-03-26 新增
import sys.smc.coupon.util.DynamicRateLimiterManager;
// end 2026-03-26 新增

import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponScheduledJob {

    private final CouponService couponService;
    private final SeckillService seckillService;
    private final SeckillActivityMapper seckillActivityMapper;
    // 2026-03-26 新增
    private final SeckillOrderMapper seckillOrderMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    // end 2026-03-26 新增
    private final DynamicRateLimiterManager dynamicRateLimiterManager;

    @Value("${seckill.warmup-minutes:10}")
    private int warmupMinutes;

    /**
     * 处理过期优惠券 - 每小时执行一次
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void processExpiredCoupons() {
        log.info("开始处理过期优惠券...");
        int count = couponService.processExpiredCoupons();
        log.info("处理过期优惠券完成, 共处理: {} 张", count);
    }

    /**
     * 秒杀活动库存预热 - 每分钟检查一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void warmUpSeckillStock() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime warmupTime = now.plusMinutes(warmupMinutes);
        
        // 查询即将开始的活动(未开始且在预热时间范围内)
        List<SeckillActivity> upcomingActivities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, SeckillStatus.PENDING.getCode())
                        .eq(SeckillActivity::getDeleted, 0)
                        .le(SeckillActivity::getStartTime, warmupTime)
                        .gt(SeckillActivity::getStartTime, now)
        );

        for (SeckillActivity activity : upcomingActivities) {
            log.info("预热秒杀活动: id={}, name={}, startTime={}", 
                    activity.getId(), activity.getName(), activity.getStartTime());
            seckillService.warmUpStock(activity);
        }
    }

    /**
     * 更新秒杀活动状态 - 每分钟执行一次
     * 
     * 2026-03-26 优化：智能判断活动是否真正结束
     */
    @Scheduled(cron = "0 * * * * ?")
    public void updateSeckillActivityStatus() {
        LocalDateTime now = LocalDateTime.now();
        
        // 1. 将已到开始时间的活动状态更新为进行中
        seckillActivityMapper.update(null, 
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SeckillActivity>()
                        .set(SeckillActivity::getStatus, SeckillStatus.ONGOING.getCode())
                        .eq(SeckillActivity::getStatus, SeckillStatus.PENDING.getCode())
                        .le(SeckillActivity::getStartTime, now)
                        .gt(SeckillActivity::getEndTime, now)
                        .eq(SeckillActivity::getDeleted, 0)
        );
        
        // 2026-03-26 优化：智能判断活动是否真正结束
        // 2. 检查已过结束时间的活动
        List<SeckillActivity> endingActivities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .in(SeckillActivity::getStatus, 
                                SeckillStatus.PENDING.getCode(), 
                                SeckillStatus.ONGOING.getCode())
                        .le(SeckillActivity::getEndTime, now)
                        .eq(SeckillActivity::getDeleted, 0)
        );
        
        for (SeckillActivity activity : endingActivities) {
            checkAndFinishActivity(activity);
        }
        // end 2026-03-26 优化
    }
    
    // 2026-03-26 新增
    /**
     * 检查并完成活动
     * 
     * 活动真正结束的条件：
     * 1. endTime已过
     * 2. DB库存=0（真没库存了）
     * 3. 无待处理订单（所有订单都处理完了）
     * 
     * 为什么需要这样？
     * - 即使endTime过了，如果还有待处理订单，可能会失败回滚库存
     * - 应该等所有订单都处理完，确认DB库存=0，才真正结束
     */
    private void checkAndFinishActivity(SeckillActivity activity) {
        Long activityId = activity.getId();
        
        // 1. 检查DB真实库存
        int dbStock = activity.getRemainStock();
        
        // 2. 检查待处理订单数量
        long pendingCount = seckillOrderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getActivityId, activityId)
                        .eq(SeckillOrder::getStatus, 0) // 待处理
        );
        
        // 3. 判断是否真正结束
        if (dbStock == 0 && pendingCount == 0) {
            // 真正结束：DB无库存 + 无待处理订单
            seckillActivityMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SeckillActivity>()
                            .set(SeckillActivity::getStatus, SeckillStatus.FINISHED.getCode())
                            .eq(SeckillActivity::getId, activityId)
            );
            
            // 清理Redis缓存
            String stockKey = RedisKeys.getSeckillStockKey(activityId);
            String activityKey = RedisKeys.getSeckillActivityKey(activityId);
            redisTemplate.delete(stockKey);
            redisTemplate.delete(activityKey);
            
            log.info("秒杀活动真正结束: activityId={}, name={}, endTime已过且DB库存=0且无待处理订单", 
                    activityId, activity.getName());
        } else {
            // 还有库存或待处理订单，暂不结束
            log.info("秒杀活动endTime已过但暂不结束: activityId={}, DB库存={}, 待处理订单={}", 
                    activityId, dbStock, pendingCount);
        }
    }
    // end 2026-03-26 新增
    
    // 2026-03-26 新增
    /**
     * 动态调整限流器 - 每5秒执行一次（优化版）
     * 
     * 优化：只处理进行中的活动，节省资源
     */
    @Scheduled(fixedRate = 5000)
    public void adjustDynamicRateLimiters() {
        // 只查询进行中的活动
        List<SeckillActivity> ongoingActivities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, SeckillStatus.ONGOING.getCode())
                        .eq(SeckillActivity::getDeleted, 0)
        );
        
        if (ongoingActivities.isEmpty()) {
            log.debug("当前无进行中的秒杀活动，跳过限流调整");
            return;
        }
        
        for (SeckillActivity activity : ongoingActivities) {
            try {
                dynamicRateLimiterManager.adjustRateIfNeeded(activity.getId());
            } catch (Exception e) {
                log.error("动态调整限流失败: activityId={}", activity.getId(), e);
            }
        }
        
        log.debug("动态限流调整完成，处理了 {} 个活动", ongoingActivities.size());
    }
    
    /**
     * 检查进行中活动是否售罄 - 每30秒执行一次
     * 
     * 场景：
     * - 活动还没到endTime
     * - 但DB库存=0 且 无待处理订单
     * - 应该自动标记为"已售罄"
     * 
     * 为什么需要这个？
     * - 提前售罄可以让Job停止无效处理
     * - 提前标记状态，前端可以显示"已售罄"而不是等到endTime
     * - 清理Redis缓存，释放资源
     */
    @Scheduled(fixedRate = 30000)
    public void checkSoldOutActivities() {
        // 查询进行中的活动
        List<SeckillActivity> ongoingActivities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, SeckillStatus.ONGOING.getCode())
                        .eq(SeckillActivity::getDeleted, 0)
        );
        
        for (SeckillActivity activity : ongoingActivities) {
            Long activityId = activity.getId();
            
            // 1. 检查DB库存
            if (activity.getRemainStock() > 0) {
                continue; // 还有库存，跳过
            }
            
            // 2. 检查待处理订单
            long pendingCount = seckillOrderMapper.selectCount(
                    new LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getActivityId, activityId)
                            .eq(SeckillOrder::getStatus, 0)
            );
            
            if (pendingCount > 0) {
                log.debug("活动库存为0但有待处理订单，暂不标记售罄: activityId={}, 待处理={}", 
                        activityId, pendingCount);
                continue; // 有待处理订单，可能会回滚库存
            }
            
            // 3. DB库存=0 且 无待处理订单 → 标记为已售罄
            seckillActivityMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SeckillActivity>()
                            .set(SeckillActivity::getStatus, SeckillStatus.SOLD_OUT.getCode())
                            .eq(SeckillActivity::getId, activityId)
            );
            
            // 清理Redis缓存
            String stockKey = RedisKeys.getSeckillStockKey(activityId);
            String activityKey = RedisKeys.getSeckillActivityKey(activityId);
            redisTemplate.delete(stockKey);
            redisTemplate.delete(activityKey);
            
            log.info("秒杀活动提前售罄: activityId={}, name={}, DB库存=0且无待处理订单", 
                    activityId, activity.getName());
        }
    }
    // end 2026-03-26 新增
}

