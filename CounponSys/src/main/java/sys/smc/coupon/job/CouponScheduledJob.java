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
        
        // 2. 将已结束的活动状态更新为已结束
        seckillActivityMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SeckillActivity>()
                        .set(SeckillActivity::getStatus, SeckillStatus.FINISHED.getCode())
                        .in(SeckillActivity::getStatus, SeckillStatus.PENDING.getCode(), SeckillStatus.ONGOING.getCode())
                        .le(SeckillActivity::getEndTime, now)
                        .eq(SeckillActivity::getDeleted, 0)
        );
    }
}

