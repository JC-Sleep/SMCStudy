package sys.smc.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.coupon.dto.request.ClaimCouponRequest;
import sys.smc.coupon.dto.request.RedeemCouponRequest;
import sys.smc.coupon.dto.response.CouponDTO;
import sys.smc.coupon.dto.response.RedeemResult;
import sys.smc.coupon.entity.Coupon;
import sys.smc.coupon.entity.CouponTemplate;
import sys.smc.coupon.entity.CouponUseRecord;
import sys.smc.coupon.enums.CouponStatus;
import sys.smc.coupon.enums.CouponType;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.exception.CouponException;
import sys.smc.coupon.mapper.CouponMapper;
import sys.smc.coupon.mapper.CouponTemplateMapper;
import sys.smc.coupon.mapper.CouponUseRecordMapper;
import sys.smc.coupon.service.CouponService;
import sys.smc.coupon.util.CouponCodeGenerator;
import sys.smc.coupon.util.RedisKeys;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 优惠券服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements CouponService {

    private final CouponMapper couponMapper;
    private final CouponTemplateMapper templateMapper;
    private final CouponUseRecordMapper useRecordMapper;
    private final CouponCodeGenerator codeGenerator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    @Value("${coupon.risk.max-claim-per-day:10}")
    private int maxClaimPerDay;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponDTO claimCoupon(ClaimCouponRequest request) {
        String userId = request.getUserId();
        Long templateId = request.getTemplateId();

        // 1. 分布式锁防止重复领取
        String lockKey = RedisKeys.getClaimLockKey(userId, templateId);
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                throw new CouponException(400, "领取处理中，请勿重复提交");
            }

            // 2. 校验每日领取次数
            checkDailyClaimLimit(userId);

            // 3. 查询模板
            CouponTemplate template = templateMapper.selectById(templateId);
            if (template == null) {
                throw CouponException.templateNotFound();
            }
            if (template.getStatus() != 1) {
                throw CouponException.templateDisabled();
            }

            // 4. 校验用户限领
            int claimedCount = couponMapper.countUserClaimed(userId, templateId);
            if (claimedCount >= template.getLimitPerUser()) {
                throw CouponException.claimLimitExceeded();
            }

            // 5. 扣减库存
            int updated = templateMapper.deductStock(templateId, 1);
            if (updated == 0) {
                throw CouponException.stockInsufficient();
            }

            // 6. 创建优惠券
            Coupon coupon = grantCoupon(userId, request.getUserMobile(), templateId, 
                    GrantType.MANUAL.getCode(), "CLAIM");

            // 7. 增加每日领取计数
            incrementDailyClaimCount(userId);

            return convertToDTO(coupon, template);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CouponException(500, "领取失败，请重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedeemResult redeemCoupon(RedeemCouponRequest request) {
        // 1. 查询优惠券
        Coupon coupon = getOne(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getCouponCode, request.getCouponCode())
                .eq(Coupon::getUserId, request.getUserId())
                .eq(Coupon::getDeleted, 0));

        if (coupon == null) {
            throw CouponException.couponNotFound();
        }
        if (coupon.getStatus() != CouponStatus.AVAILABLE.getCode()) {
            if (coupon.getStatus() == CouponStatus.USED.getCode()) {
                throw CouponException.couponUsed();
            }
            if (coupon.getStatus() == CouponStatus.EXPIRED.getCode()) {
                throw CouponException.couponExpired();
            }
            throw CouponException.couponNotAvailable();
        }

        // 2. 校验有效期
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getValidStartTime()) || now.isAfter(coupon.getValidEndTime())) {
            throw CouponException.couponExpired();
        }

        // 3. 校验门槛金额
        if (coupon.getThresholdAmount() != null && 
            request.getOriginalAmount().compareTo(coupon.getThresholdAmount()) < 0) {
            throw CouponException.amountNotMet();
        }

        // 4. 计算优惠金额
        BigDecimal discountAmount = calculateDiscount(coupon, request.getOriginalAmount());

        // 5. 核销优惠券
        int updated = couponMapper.redeemCoupon(coupon.getId(), request.getOrderNo());
        if (updated == 0) {
            throw new CouponException(400, "核销失败，优惠券状态已变更");
        }

        // 6. 记录使用记录
        CouponUseRecord record = new CouponUseRecord();
        record.setCouponId(coupon.getId());
        record.setCouponCode(coupon.getCouponCode());
        record.setUserId(request.getUserId());
        record.setOrderNo(request.getOrderNo());
        record.setOriginalAmount(request.getOriginalAmount());
        record.setDiscountAmount(discountAmount);
        record.setActualAmount(request.getOriginalAmount().subtract(discountAmount));
        record.setUseScene(request.getUseScene());
        record.setUseTime(now);
        useRecordMapper.insert(record);

        return RedeemResult.success(request.getOriginalAmount(), discountAmount);
    }

    @Override
    public List<CouponDTO> listUserCoupons(String userId, Integer status) {
        LambdaQueryWrapper<Coupon> query = new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getUserId, userId)
                .eq(Coupon::getDeleted, 0)
                .orderByDesc(Coupon::getCreateTime);
        
        if (status != null) {
            query.eq(Coupon::getStatus, status);
        }

        List<Coupon> coupons = list(query);
        return coupons.stream()
                .map(c -> {
                    CouponTemplate template = templateMapper.selectById(c.getTemplateId());
                    return convertToDTO(c, template);
                })
                .collect(Collectors.toList());
    }

    @Override
    public CouponDTO getCouponByCode(String couponCode) {
        Coupon coupon = getOne(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getCouponCode, couponCode)
                .eq(Coupon::getDeleted, 0));
        if (coupon == null) {
            throw CouponException.couponNotFound();
        }
        CouponTemplate template = templateMapper.selectById(coupon.getTemplateId());
        return convertToDTO(coupon, template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Coupon grantCoupon(String userId, String userMobile, Long templateId, 
                               Integer grantType, String grantSource) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw CouponException.templateNotFound();
        }

        // 计算有效期
        LocalDateTime validStart, validEnd;
        if (template.getValidityType() == 1) {
            // 绝对时间
            validStart = template.getValidStartTime();
            validEnd = template.getValidEndTime();
        } else {
            // 相对时间
            validStart = LocalDateTime.now();
            validEnd = validStart.plusDays(template.getValidDays());
        }

        // 创建优惠券
        Coupon coupon = new Coupon();
        coupon.setCouponCode(codeGenerator.generateCode());
        coupon.setTemplateId(templateId);
        coupon.setUserId(userId);
        coupon.setUserMobile(userMobile);
        coupon.setCouponType(template.getCouponType());
        coupon.setFaceValue(template.getFaceValue());
        coupon.setDiscountRate(template.getDiscountRate());
        coupon.setThresholdAmount(template.getThresholdAmount());
        coupon.setStatus(CouponStatus.AVAILABLE.getCode());
        coupon.setValidStartTime(validStart);
        coupon.setValidEndTime(validEnd);
        coupon.setClaimTime(LocalDateTime.now());
        coupon.setGrantType(grantType);
        coupon.setGrantSource(grantSource);
        coupon.setDeleted(0);

        save(coupon);
        log.info("发放优惠券成功: userId={}, couponCode={}, templateId={}", 
                userId, coupon.getCouponCode(), templateId);

        return coupon;
    }

    @Override
    public int processExpiredCoupons() {
        int count = couponMapper.updateExpiredCoupons();
        log.info("处理过期优惠券: {} 张", count);
        return count;
    }

    /**
     * 计算优惠金额
     */
    private BigDecimal calculateDiscount(Coupon coupon, BigDecimal originalAmount) {
        CouponType type = CouponType.fromCode(coupon.getCouponType());
        switch (type) {
            case CASH:
            case FULL_REDUCTION:
                // 现金券/满减券: 直接减面值
                return coupon.getFaceValue().min(originalAmount);
            case DISCOUNT:
                // 折扣券: 计算折扣
                BigDecimal discountRate = coupon.getDiscountRate();
                return originalAmount.multiply(BigDecimal.ONE.subtract(discountRate))
                        .setScale(2, RoundingMode.HALF_UP);
            case FREE:
                // 免单券: 全免
                return originalAmount;
            default:
                return BigDecimal.ZERO;
        }
    }

    /**
     * 校验每日领取限制
     */
    private void checkDailyClaimLimit(String userId) {
        String key = RedisKeys.USER_CLAIM_COUNT + userId;
        Object count = redisTemplate.opsForValue().get(key);
        if (count != null && Integer.parseInt(count.toString()) >= maxClaimPerDay) {
            throw CouponException.dailyLimitExceeded();
        }
    }

    /**
     * 增加每日领取计数
     */
    private void incrementDailyClaimCount(String userId) {
        String key = RedisKeys.USER_CLAIM_COUNT + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 首次增加,设置到当天结束过期
            redisTemplate.expire(key, Duration.ofDays(1));
        }
    }

    /**
     * 转换为DTO
     */
    private CouponDTO convertToDTO(Coupon coupon, CouponTemplate template) {
        CouponDTO dto = new CouponDTO();
        dto.setId(coupon.getId());
        dto.setCouponCode(coupon.getCouponCode());
        dto.setTemplateId(coupon.getTemplateId());
        dto.setTemplateName(template != null ? template.getName() : null);
        dto.setCouponTypeName(CouponType.fromCode(coupon.getCouponType()).getName());
        dto.setFaceValue(coupon.getFaceValue());
        dto.setDiscountRate(coupon.getDiscountRate());
        dto.setThresholdAmount(coupon.getThresholdAmount());
        dto.setStatusName(CouponStatus.fromCode(coupon.getStatus()).getName());
        dto.setValidStartTime(coupon.getValidStartTime());
        dto.setValidEndTime(coupon.getValidEndTime());
        dto.setClaimTime(coupon.getClaimTime());
        dto.setDescription(template != null ? template.getDescription() : null);
        return dto;
    }
}

