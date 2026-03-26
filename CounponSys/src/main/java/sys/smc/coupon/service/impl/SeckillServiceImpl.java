package sys.smc.coupon.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import sys.smc.coupon.config.KafkaConfig;
import sys.smc.coupon.dto.request.SeckillGrabRequest;
import sys.smc.coupon.dto.response.SeckillActivityDTO;
import sys.smc.coupon.dto.response.SeckillGrabResult;
import sys.smc.coupon.entity.CouponTemplate;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.entity.SeckillOrder;
import sys.smc.coupon.enums.PointsChannel;
import sys.smc.coupon.enums.SeckillStatus;
import sys.smc.coupon.exception.SeckillException;
import sys.smc.coupon.mapper.CouponTemplateMapper;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.mapper.SeckillOrderMapper;
import sys.smc.coupon.service.SeckillService;
import sys.smc.coupon.util.RedisKeys;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 秒杀服务实现
 * 
 * 高并发场景使用Kafka (支持10W+ QPS)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final CouponTemplateMapper templateMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> seckillGrabScript;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public SeckillGrabResult grabCoupon(SeckillGrabRequest request) {
        Long activityId = request.getActivityId();
        String userId = request.getUserId();

        // 1. 从Redis获取活动信息(缓存)
        SeckillActivity activity = getActivityFromCache(activityId);
        if (activity == null) {
            throw SeckillException.activityNotFound();
        }

        // 2. 校验活动状态和时间
        validateActivity(activity, request);

        // 3. 执行Lua脚本原子扣减库存
        String stockKey = RedisKeys.getSeckillStockKey(activityId);
        String userKey = RedisKeys.getSeckillUserKey(activityId, userId);
        
        Long result = redisTemplate.execute(
                seckillGrabScript,
                Arrays.asList(stockKey, userKey),
                activity.getLimitPerUser(),
                1 // 抢购数量
        );

        if (result == null || result < 0) {
            return handleLuaResult(result);
        }

        // 4. 抢购成功,生成订单号
        String orderNo = generateOrderNo();
        
        // 5. 创建秒杀订单(待处理状态)
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(orderNo);
        order.setActivityId(activityId);
        order.setUserId(userId);
        order.setUserMobile(request.getUserMobile());
        order.setPointsChannel(request.getPointsChannel());
        order.setPointsUsed(activity.getRequiredPoints());
        order.setAmountPaid(activity.getRequiredAmount());
        order.setStatus(0); // 待处理
        order.setGrabTime(LocalDateTime.now());
        orderMapper.insert(order);


        // 6. 发送Kafka消息异步处理(扣积分、发券等)
        // 使用userId作为Key，保证同一用户的订单有序处理
        kafkaTemplate.send(KafkaConfig.TOPIC_SECKILL_ORDER, userId, order.getId().toString());
        
        log.info("秒杀抢购成功: userId={}, activityId={}, orderNo={}", userId, activityId, orderNo);
        
        return SeckillGrabResult.builder()
                .success(true)
                .orderNo(orderNo)
                .message("抢购成功,正在处理中")
                .pointsUsed(activity.getRequiredPoints())
                .amountPaid(activity.getRequiredAmount())
                .build();
    }

    @Override
    public List<SeckillActivityDTO> listActivities(Integer status) {
        LambdaQueryWrapper<SeckillActivity> query = new LambdaQueryWrapper<SeckillActivity>()
                .eq(SeckillActivity::getDeleted, 0)
                .orderByAsc(SeckillActivity::getStartTime);
        
        if (status != null) {
            query.eq(SeckillActivity::getStatus, status);
        } else {
            // 默认只查询未结束的活动
            query.in(SeckillActivity::getStatus, 0, 1);
        }

        List<SeckillActivity> activities = activityMapper.selectList(query);
        return activities.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public SeckillActivityDTO getActivityDetail(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw SeckillException.activityNotFound();
        }
        return convertToDTO(activity);
    }

    @Override
    public Integer getRemainStock(Long activityId) {
        String stockKey = RedisKeys.getSeckillStockKey(activityId);
        Object stock = redisTemplate.opsForValue().get(stockKey);
        if (stock != null) {
            return Integer.parseInt(stock.toString());
        }
        // Redis没有则查数据库
        SeckillActivity activity = activityMapper.selectById(activityId);
        return activity != null ? activity.getRemainStock() : 0;
    }

    @Override
    public void warmUpStock(SeckillActivity activity) {
        String stockKey = RedisKeys.getSeckillStockKey(activity.getId());
        String activityKey = RedisKeys.getSeckillActivityKey(activity.getId());
        
        // 计算过期时间(活动结束后1小时)
        long expireSeconds = Duration.between(LocalDateTime.now(), 
                activity.getEndTime().plusHours(1)).getSeconds();
        
        if (expireSeconds > 0) {
            // 预热库存
            redisTemplate.opsForValue().set(stockKey, activity.getRemainStock(), 
                    expireSeconds, TimeUnit.SECONDS);
            // 缓存活动信息
            redisTemplate.opsForValue().set(activityKey, activity, 
                    expireSeconds, TimeUnit.SECONDS);
            
            log.info("秒杀活动预热完成: activityId={}, stock={}", 
                    activity.getId(), activity.getRemainStock());
        }
    }

    @Override
    public Integer getUserGrabbedCount(Long activityId, String userId) {
        String userKey = RedisKeys.getSeckillUserKey(activityId, userId);
        Object count = redisTemplate.opsForValue().get(userKey);
        return count != null ? Integer.parseInt(count.toString()) : 0;
    }

    /**
     * 从缓存获取活动信息
     */
    private SeckillActivity getActivityFromCache(Long activityId) {
        String activityKey = RedisKeys.getSeckillActivityKey(activityId);
        Object cached = redisTemplate.opsForValue().get(activityKey);
        if (cached != null) {
            return (SeckillActivity) cached;
        }
        // 缓存没有则查数据库
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity != null && activity.getDeleted() == 0) {
            // 回写缓存
            warmUpStock(activity);
        }
        return activity;
    }

    /**
     * 校验活动
     */
    private void validateActivity(SeckillActivity activity, SeckillGrabRequest request) {
        LocalDateTime now = LocalDateTime.now();
        
        // 校验活动时间
        if (now.isBefore(activity.getStartTime())) {
            throw SeckillException.activityNotStarted();
        }
        if (now.isAfter(activity.getEndTime())) {
            throw SeckillException.activityEnded();
        }
        
        // 校验活动状态
        if (activity.getStatus() == SeckillStatus.FINISHED.getCode() ||
            activity.getStatus() == SeckillStatus.SOLD_OUT.getCode()) {
            throw SeckillException.activityEnded();
        }
        if (activity.getStatus() == SeckillStatus.PAUSED.getCode()) {
            throw new SeckillException(400, "活动已暂停");
        }

        // 校验VIP
        if (activity.getVipOnly() == 1) {
            if (request.getVipLevel() == null || request.getVipLevel() < activity.getVipLevelRequired()) {
                throw SeckillException.vipLevelNotMet();
            }
        }
    }

    /**
     * 处理Lua脚本返回结果
     * 
     * 2026-03-26 优化：库存不足时智能判断提示信息
     */
    private SeckillGrabResult handleLuaResult(Long result, Long activityId) {
        if (result == null) {
            return SeckillGrabResult.fail("系统异常");
        }
        switch (result.intValue()) {
            case -1:
                // 2026-03-26 优化：库存不足时，智能判断是临时不足还是真正售罄
                return handleStockInsufficient(activityId);
            case -2:
                throw SeckillException.grabLimitExceeded();
            case -3:
                throw SeckillException.activityNotFound();
            default:
                return SeckillGrabResult.fail("抢购失败");
        }
    }

    // 2026-03-26 新增
    /**
     * 处理库存不足（智能判断）
     * 
     * 场景：
     * 1. Redis库存=0，但DB还有库存 → "活动火爆，请稍后重试"（可能有回滚）
     * 2. Redis库存=0，DB也=0，但有待处理订单 → "活动火爆，请稍后重试"（等待处理结果）
     * 3. Redis库存=0，DB也=0，无待处理订单 → "已售罄"（真正售罄）
     * 
     * 为什么这样设计？
     * - 场景1：DB库存>0说明Kafka还没消费完，或者有失败回滚的可能
     * - 场景2：有待处理订单，可能会失败回滚库存
     * - 场景3：DB和Redis都没库存，且订单都处理完了，确认售罄
     */
    private SeckillGrabResult handleStockInsufficient(Long activityId) {
        // 1. 检查DB真实库存
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw SeckillException.activityNotFound();
        }
        
        // 2. 检查是否有待处理订单
        long pendingCount = orderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getActivityId, activityId)
                        .eq(SeckillOrder::getStatus, 0) // 待处理
        );
        
        // 3. 智能判断
        if (activity.getRemainStock() > 0 || pendingCount > 0) {
            // DB还有库存 或 有订单正在处理（可能会回滚库存）
            log.info("Redis库存临时不足: activityId={}, DB库存={}, 待处理订单={}", 
                    activityId, activity.getRemainStock(), pendingCount);
            throw SeckillException.stockInsufficientRetry();
        } else {
            // DB库存也为0，且无待处理订单，真的售罄了
            log.info("活动真正售罄: activityId={}, DB库存={}, 待处理订单={}", 
                    activityId, activity.getRemainStock(), pendingCount);
            throw SeckillException.soldOut();
        }
    }
    // end 2026-03-26 新增

    /**
     * 生成订单号
     */
    private String generateOrderNo() {
        return "SK" + System.currentTimeMillis() + IdUtil.fastSimpleUUID().substring(0, 6).toUpperCase();
    }

    /**
     * 转换为DTO
     */
    private SeckillActivityDTO convertToDTO(SeckillActivity activity) {
        SeckillActivityDTO dto = new SeckillActivityDTO();
        dto.setId(activity.getId());
        dto.setName(activity.getName());
        dto.setDescription(activity.getDescription());
        dto.setStartTime(activity.getStartTime());
        dto.setEndTime(activity.getEndTime());
        dto.setTotalStock(activity.getTotalStock());
        dto.setRemainStock(getRemainStock(activity.getId()));
        dto.setLimitPerUser(activity.getLimitPerUser());
        dto.setRequiredPoints(activity.getRequiredPoints());
        dto.setRequiredAmount(activity.getRequiredAmount());
        dto.setVipOnly(activity.getVipOnly() == 1);
        dto.setStatusName(SeckillStatus.fromCode(activity.getStatus()).getName());

        // 设置积分渠道名称
        if (activity.getPointsChannel() != null) {
            dto.setPointsChannelName(PointsChannel.fromCode(activity.getPointsChannel()).getName());
        }

        // 设置券模板信息
        CouponTemplate template = templateMapper.selectById(activity.getTemplateId());
        if (template != null) {
            dto.setTemplateName(template.getName());
            dto.setCouponFaceValue(template.getFaceValue());
        }

        // 计算距离开始时间
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            dto.setSecondsToStart(Duration.between(now, activity.getStartTime()).getSeconds());
        }

        return dto;
    }
}

