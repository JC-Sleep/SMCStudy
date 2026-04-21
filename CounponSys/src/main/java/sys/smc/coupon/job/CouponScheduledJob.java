package sys.smc.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sys.smc.coupon.entity.GrantTask;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.enums.SeckillStatus;
import sys.smc.coupon.mapper.CouponRedeemCodeMapper;
import sys.smc.coupon.mapper.GrantTaskMapper;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.service.CouponService;
import sys.smc.coupon.service.SeckillService;
import sys.smc.coupon.service.impl.CouponServiceImpl;
import sys.smc.coupon.util.DynamicRateLimiterManager;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.time.LocalDateTime;

/**
 * 优惠券定时任务
 * 2026-04-20 新增：
 *   ① expireRedeemCodes()     — 兑换码过期Job（修复缺失瑕疵）
 *   ② retryPendingGrantTasks()— 发券补偿重试Job（修复分布式事务瑕疵）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponScheduledJob {

    private final CouponService couponService;
    private final SeckillService seckillService;
    private final SeckillActivityMapper seckillActivityMapper;
    private final DynamicRateLimiterManager dynamicRateLimiterManager;

    // 2026-04-20 新增
    private final CouponRedeemCodeMapper redeemCodeMapper;
    private final GrantTaskMapper grantTaskMapper;
    private final CouponServiceImpl couponServiceImpl;
    // end 2026-04-20 新增

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

    // ============================================================
    // 2026-04-20 修复：兑换码过期Job（原来只有懒检查，统计数据不准）
    // ============================================================

    /**
     * 批量标记过期兑换码（修复瑕疵③）
     *
     * 问题：T_COUPON_REDEEM_CODE 表只在用户提交时懒检查过期
     *      导致大量STATUS=0的码实际上已过期，统计数据不准确
     *      （"还有多少可用码"显示虚高）
     *
     * 修复：每小时批量扫描，将 STATUS=0 且 EXPIRE_TIME<NOW 的码更新为 STATUS=2
     *      每次最多处理5000条，防止大事务锁表
     *
     * 执行时间：每小时第5分钟（错开整点，避免与 processExpiredCoupons 同时执行）
     */
    @Scheduled(cron = "0 5 * * * ?")
    public void expireRedeemCodes() {
        log.info("[兑换码过期Job] 开始批量标记过期兑换码...");
        try {
            int total = 0;
            int batchSize = 5000;
            int affected;
            // 循环处理，直到没有需要过期的码（防止单次5000条处理不完）
            do {
                affected = redeemCodeMapper.batchExpire(batchSize);
                total += affected;
                if (affected > 0) {
                    log.info("[兑换码过期Job] 本批次标记 {} 条过期，累计 {} 条", affected, total);
                }
            } while (affected == batchSize); // 若刚好处理了5000条，可能还有更多
            log.info("[兑换码过期Job] 完成，共标记 {} 条兑换码为过期", total);
        } catch (Exception e) {
            log.error("[兑换码过期Job] 执行失败", e);
        }
    }

    // ============================================================
    // 2026-04-20 修复：发券补偿重试Job（解决分布式事务问题）
    // ============================================================

    /**
     * 重试待处理的发券补偿任务（修复瑕疵①）
     *
     * 背景：CAS成功（码标记使用）后，grantCoupon()可能失败（外部服务超时/DB抖动）
     *      原代码直接报错，导致"码用掉了但用户没收到券"
     *
     * 修复流程：
     *   CAS成功 + 写T_GRANT_TASK（同一事务） → 本Job重试 → 最终用户收到券
     *
     * 重试策略（指数退避）：
     *   第1次失败 → 1分钟后重试
     *   第2次失败 → 5分钟后重试
     *   第3次失败 → 30分钟后重试 → 超限标记status=2，人工告警
     *
     * 执行频率：每分钟检查一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void retryPendingGrantTasks() {
        List<GrantTask> tasks = grantTaskMapper.selectPendingTasks(new Date());
        if (tasks.isEmpty()) return;

        log.info("[发券补偿Job] 发现 {} 个待处理任务", tasks.size());
        for (GrantTask task : tasks) {
            processGrantTask(task);
        }
    }

    private void processGrantTask(GrantTask task) {
        try {
            // 幂等检查：若已有成功记录，直接标记完成
            if (grantTaskMapper.countSuccessByCode(task.getRedeemCode()) > 0) {
                grantTaskMapper.markSuccess(task.getId(), task.getCouponId());
                log.info("[发券补偿Job] 幂等跳过 taskId={} code={}", task.getId(), task.getRedeemCode());
                return;
            }

            // 重试发券
            sys.smc.coupon.entity.Coupon issued = couponServiceImpl.grantCoupon(
                    task.getUserId(), task.getUserId(), task.getTemplateId(), 7, "REDEEM_CODE_RETRY");

            grantTaskMapper.markSuccess(task.getId(), issued.getId());
            log.info("[发券补偿Job] 重试成功 taskId={} code={} couponId={}",
                    task.getId(), task.getRedeemCode(), issued.getId());

        } catch (Exception e) {
            // 计算下次重试时间（指数退避）
            int retryCount = task.getRetryCount() != null ? task.getRetryCount() : 0;
            int delayMinutes = retryCount < GrantTask.RETRY_DELAYS_MINUTES.length
                    ? GrantTask.RETRY_DELAYS_MINUTES[retryCount]
                    : 60; // 超出预设，默认1小时
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.MINUTE, delayMinutes);

            grantTaskMapper.markFailed(task.getId(), e.getMessage(), cal.getTime());

            // 超限时人工告警
            if (retryCount + 1 >= (task.getMaxRetry() != null ? task.getMaxRetry() : 3)) {
                log.error("[发券补偿Job][人工告警🚨] taskId={} code={} userId={} 重试{}次失败，需人工处理！原因: {}",
                        task.getId(), task.getRedeemCode(), task.getUserId(),
                        retryCount + 1, e.getMessage());
                // TODO: 接入告警系统（Slack/钉钉/邮件）
            } else {
                log.warn("[发券补偿Job] 重试失败 taskId={} retryCount={} 下次重试: {}分钟后",
                        task.getId(), retryCount + 1, delayMinutes);
            }
        }
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

