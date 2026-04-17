package sys.smc.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.coupon.entity.CouponRedeemCode;
import sys.smc.coupon.entity.CouponTemplate;
import sys.smc.coupon.entity.RedeemCodeBatch;
import sys.smc.coupon.exception.CouponException;
import sys.smc.coupon.mapper.CouponRedeemCodeMapper;
import sys.smc.coupon.mapper.CouponTemplateMapper;
import sys.smc.coupon.mapper.RedeemCodeBatchMapper;
import sys.smc.coupon.util.RedeemCodeGenerator;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 兑换码服务 —— 防刷兑换完整实现
 *
 * ══════════════════════════════════════════════════════
 * 兑换码防刷多层防御体系：
 *
 * 第1层：HMAC签名校验（格式层）
 *   - 无效码（随便乱输）直接拦截，不查DB
 *   - 攻击者无法枚举有效码（签名是私钥生成的）
 *
 * 第2层：IP限速（网络层）
 *   - 同一IP每分钟最多尝试N次（默认10次）
 *   - 防止单机/单代理高频扫码
 *
 * 第3层：用户限速（业务层）
 *   - 同一用户每分钟最多兑换N次（默认5次）
 *   - 防止羊毛党批量账号刷
 *
 * 第4层：分布式锁（并发层）
 *   - 同一code同一时刻只允许一个线程处理
 *   - 防止并发重复兑换（双击提交等）
 *
 * 第5层：DB CAS原子更新（数据层）
 *   - WHERE CODE=? AND STATUS=0 → 更新状态为1
 *   - 即使锁失效，DB层也保证不重复兑换
 *   - DB UNIQUE约束兜底
 *
 * 第6层：失败次数锁定（惩罚层）
 *   - 同一码失败5次自动锁定
 *   - 防止对单个码的暴力重试
 * ══════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedeemCodeServiceImpl {

    private final CouponRedeemCodeMapper redeemCodeMapper;
    private final RedeemCodeBatchMapper batchMapper;
    private final CouponTemplateMapper templateMapper;
    private final RedeemCodeGenerator codeGenerator;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final CouponServiceImpl couponService;
    // userId→mobile 实际项目中从用户服务查询；这里简化直接传userId作为mobile

    @Value("${coupon.code.redeem.ip-rate-limit:10}")
    private int ipRateLimit;

    @Value("${coupon.code.redeem.user-rate-limit:5}")
    private int userRateLimit;

    // Redis Key前缀
    private static final String LOCK_PREFIX     = "coupon:redeem:lock:";
    private static final String IP_RATE_PREFIX  = "coupon:redeem:ip:";
    private static final String USER_RATE_PREFIX = "coupon:redeem:user:";

    // ======================== 批量生成兑换码 ========================

    /**
     * 批量生成兑换码并持久化
     *
     * @param templateId  券模板ID
     * @param count       生成数量（建议单次不超过50000）
     * @param expireTime  兑换码过期时间
     * @param batchName   批次名称（如：2026春节活动）
     * @param operatorId  操作人
     * @return            批次ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long generateBatch(Long templateId, int count, Date expireTime,
                              String batchName, String operatorId) {
        if (count <= 0 || count > 100_000) {
            throw new CouponException("单次生成数量必须在1~100000之间");
        }

        // 校验模板
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || template.getStatus() != 1) {
            throw new CouponException("券模板不存在或未启用");
        }

        // 创建批次
        RedeemCodeBatch batch = new RedeemCodeBatch();
        batch.setId(IdWorker.getId());
        batch.setBatchName(batchName);
        batch.setTemplateId(templateId);
        batch.setTotalCount(count);
        batch.setRedeemedCount(0);
        batch.setStatus(1); // 直接激活
        batch.setExpireTime(expireTime);
        batch.setCreateBy(operatorId);
        batchMapper.insert(batch);

        // 批量生成兑换码（分批插入，每批2000条）
        List<CouponRedeemCode> batchList = new ArrayList<>(2000);
        for (int i = 0; i < count; i++) {
            Long codeId = IdWorker.getId();
            String code = codeGenerator.generate(codeId);

            CouponRedeemCode redeemCode = new CouponRedeemCode();
            redeemCode.setId(codeId);
            redeemCode.setCode(code);
            redeemCode.setBatchId(batch.getId());
            redeemCode.setTemplateId(templateId);
            redeemCode.setStatus(0);
            redeemCode.setFailCount(0);
            redeemCode.setExpireTime(expireTime);
            batchList.add(redeemCode);

            if (batchList.size() == 2000 || i == count - 1) {
                redeemCodeMapper.batchInsert(batchList);
                batchList.clear();
            }
        }

        log.info("[兑换码生成] 批次={} 模板={} 数量={} 操作人={}", batch.getId(), templateId, count, operatorId);
        return batch.getId();
    }

    // ======================== 兑换码核销 ========================

    /**
     * 兑换码兑换 —— 主流程（含全部防刷逻辑）
     *
     * @param code     用户输入的兑换码
     * @param userId   当前用户ID
     * @param clientIp 客户端IP（从HttpServletRequest获取）
     * @param channel  兑换渠道 APP/WEB/H5
     * @return         发放成功后的券实例ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long redeem(String code, String userId, String clientIp, String channel) {
        code = code.toUpperCase().trim();

        // ───────── 第1层：HMAC签名校验 ─────────
        if (!codeGenerator.verify(code)) {
            log.warn("[兑换防刷] HMAC签名无效 code={} ip={} userId={}", code, clientIp, userId);
            throw new CouponException(429, "无效的兑换码");
        }

        // ───────── 第2层：IP限速 ─────────
        checkIpRateLimit(clientIp);

        // ───────── 第3层：用户限速 ─────────
        checkUserRateLimit(userId);

        // ───────── 第4层：分布式锁（同一code同时只处理一次）─────────
        String lockKey = LOCK_PREFIX + code;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new CouponException(429, "兑换请求频繁，请稍后重试");
            }
            return doRedeem(code, userId, clientIp, channel);
        } catch (CouponException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CouponException("系统繁忙，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * 真正的兑换逻辑（已在分布式锁内）
     */
    private Long doRedeem(String code, String userId, String clientIp, String channel) {
        // 查询兑换码
        CouponRedeemCode redeemCode = redeemCodeMapper.selectOne(
                new LambdaQueryWrapper<CouponRedeemCode>()
                        .eq(CouponRedeemCode::getCode, code)
                        .eq(CouponRedeemCode::getDeleted, 0)
        );

        if (redeemCode == null) {
            log.warn("[兑换防刷] 码不存在 code={} userId={}", code, userId);
            throw new CouponException(404, "兑换码不存在");
        }

        // 检查状态
        switch (redeemCode.getStatus()) {
            case 1:
                throw new CouponException(400, "该兑换码已被使用");
            case 2:
                throw new CouponException(400, "该兑换码已过期");
            case 3:
                throw new CouponException(400, "该兑换码已被锁定（多次输错）");
        }

        // 检查是否过期
        if (redeemCode.getExpireTime() != null && redeemCode.getExpireTime().before(new Date())) {
            // 更新为过期状态
            CouponRedeemCode update = new CouponRedeemCode();
            update.setId(redeemCode.getId());
            update.setStatus(2);
            redeemCodeMapper.updateById(update);
            throw new CouponException(400, "该兑换码已过期");
        }

        // ───────── 第5层：DB CAS原子更新（防并发重入）─────────
        int affected = redeemCodeMapper.casRedeemCode(
                code, userId, new Date(), clientIp, channel);

        if (affected == 0) {
            // CAS失败：已被其他请求抢先兑换（并发场景）
            // ───────── 第6层：累计失败次数 ─────────
            redeemCodeMapper.incrementFailCount(code);
            log.warn("[兑换防刷] CAS失败（并发抢兑）code={} userId={}", code, userId);
            throw new CouponException(409, "兑换失败，该兑换码已被使用");
        }

        // 更新批次已兑换数量
        updateBatchRedeemedCount(redeemCode.getBatchId());

        // 发放优惠券到用户账户（grantCoupon: userId, mobile, templateId, grantType=7手动, source）
        // 实际项目可从用户服务查询mobile，这里简化传userId
        sys.smc.coupon.entity.Coupon issued = couponService.grantCoupon(
                userId, userId, redeemCode.getTemplateId(), 7, "REDEEM_CODE");
        Long couponId = issued.getId();
        log.info("[兑换成功] code={} userId={} couponId={} channel={}", code, userId, couponId, channel);
        return couponId;
    }

    // ======================== 防刷辅助方法 ========================

    /** IP限速：同IP每分钟最多 ipRateLimit 次 */
    private void checkIpRateLimit(String ip) {
        String key = IP_RATE_PREFIX + ip;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }
        if (count != null && count > ipRateLimit) {
            log.warn("[兑换防刷] IP限速触发 ip={} count={}", ip, count);
            throw new CouponException(429, "操作过于频繁，请1分钟后重试");
        }
    }

    /** 用户限速：同用户每分钟最多 userRateLimit 次 */
    private void checkUserRateLimit(String userId) {
        String key = USER_RATE_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }
        if (count != null && count > userRateLimit) {
            log.warn("[兑换防刷] 用户限速触发 userId={} count={}", userId, count);
            throw new CouponException(429, "兑换过于频繁，请1分钟后重试");
        }
    }

    /** 更新批次已兑换数量 */
    private void updateBatchRedeemedCount(Long batchId) {
        if (batchId == null) return;
        try {
            RedeemCodeBatch batch = batchMapper.selectById(batchId);
            if (batch != null) {
                batch.setRedeemedCount(batch.getRedeemedCount() + 1);
                batchMapper.updateById(batch);
            }
        } catch (Exception e) {
            log.error("[兑换码] 更新批次兑换数失败 batchId={}", batchId, e);
        }
    }

    // ======================== 查询接口 ========================

    /** 查询批次下的兑换码列表（管理后台用） */
    public List<CouponRedeemCode> listByBatch(Long batchId) {
        return redeemCodeMapper.selectList(
                new LambdaQueryWrapper<CouponRedeemCode>()
                        .eq(CouponRedeemCode::getBatchId, batchId)
                        .eq(CouponRedeemCode::getDeleted, 0)
                        .orderByDesc(CouponRedeemCode::getCreateTime)
        );
    }

    /** 查询批次信息 */
    public RedeemCodeBatch getBatch(Long batchId) {
        return batchMapper.selectById(batchId);
    }
}





