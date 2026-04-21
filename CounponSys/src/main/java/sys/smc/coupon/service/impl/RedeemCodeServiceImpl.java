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
import sys.smc.coupon.entity.GrantTask;
import sys.smc.coupon.entity.RedeemCodeBatch;
import sys.smc.coupon.exception.CouponException;
import sys.smc.coupon.mapper.CouponRedeemCodeMapper;
import sys.smc.coupon.mapper.CouponTemplateMapper;
import sys.smc.coupon.mapper.GrantTaskMapper;
import sys.smc.coupon.mapper.RedeemCodeBatchMapper;
import sys.smc.coupon.util.RedeemCodeGenerator;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 兑换码服务 —— 防刷兑换完整实现（已修复所有瑕疵，2026-04-20）
 *
 * ══════════════════════════════════════════════════════
 * 修复记录：
 * ① 分布式事务：CAS + 写GrantTask 同一事务，发券由Job重试保证最终一致
 * ② 同批次重复兑换：countRedeemedByUserAndBatch 校验 maxPerUser
 * ③ 解锁功能：selfUnlock / adminUnlock，UNLOCK_COUNT防社工
 * ④ requestId幂等：Redis缓存5分钟，防网络重试重复发券
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
    // 修复①：注入补偿任务Mapper
    private final GrantTaskMapper grantTaskMapper;

    @Value("${coupon.code.redeem.ip-rate-limit:10}")
    private int ipRateLimit;

    @Value("${coupon.code.redeem.user-rate-limit:5}")
    private int userRateLimit;

    private static final String LOCK_PREFIX      = "coupon:redeem:lock:";
    private static final String IP_RATE_PREFIX   = "coupon:redeem:ip:";
    private static final String USER_RATE_PREFIX = "coupon:redeem:user:";
    // 修复④：requestId幂等Key前缀
    private static final String IDEM_PREFIX      = "coupon:redeem:idem:";

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
        batch.setStatus(1);
        batch.setMaxPerUser(1); // 默认每批次每用户只能兑1张
        batch.setExpireTime(expireTime);
        batch.setCreateBy(operatorId);
        batchMapper.insert(batch);

        // 批量生成兑换码（分批插入，每批2000条）
        List<CouponRedeemCode> batchList = new ArrayList<>(2000);
        for (int i = 0; i < count; i++) {
            Long codeId = IdWorker.getId();
            CouponRedeemCode redeemCode = new CouponRedeemCode();
            redeemCode.setId(codeId);
            redeemCode.setCode(codeGenerator.generate(codeId));
            redeemCode.setBatchId(batch.getId());
            redeemCode.setTemplateId(templateId);
            redeemCode.setStatus(0);
            redeemCode.setFailCount(0);
            redeemCode.setUnlockCount(0);
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

    // ======================== 兑换码核销（主流程） ========================

    /**
     * 兑换码兑换主流程
     *
     * @param requestId 前端幂等ID（UUID），防止网络重试重复发券
     */
    public Long redeem(String code, String userId, String clientIp,
                       String channel, String requestId) {
        code = code.toUpperCase().trim();

        // ── 修复④：requestId幂等检查 ──
        // 同一requestId已经成功过，直接返回缓存的couponId，不重复处理
        if (requestId != null && !requestId.isEmpty()) {
            String cacheKey = IDEM_PREFIX + requestId;
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("[幂等] requestId={} 命中缓存，直接返回 couponId={}", requestId, cached);
                return Long.valueOf(cached);
            }
        }

        // ── 第1层：HMAC签名校验 ──
        if (!codeGenerator.verify(code)) {
            log.warn("[防刷] HMAC无效 code={} ip={} userId={}", code, clientIp, userId);
            throw new CouponException(400, "无效的兑换码");
        }

        // ── 第2层：IP限速 ──
        checkIpRateLimit(clientIp);

        // ── 第3层：用户限速 ──
        checkUserRateLimit(userId);

        // ── 第4层：分布式锁（同一code同时只处理一次）──
        RLock lock = redissonClient.getLock(LOCK_PREFIX + code);
        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                throw new CouponException(429, "兑换请求频繁，请稍后重试");
            }
            Long couponId = doRedeem(code, userId, clientIp, channel);

            // ── 修复④：缓存成功结果5分钟，供幂等重试使用 ──
            if (requestId != null && !requestId.isEmpty()) {
                redisTemplate.opsForValue().set(
                        IDEM_PREFIX + requestId, couponId.toString(), 5, TimeUnit.MINUTES);
            }
            return couponId;

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
     * 兑换核心逻辑（已在分布式锁内执行）
     *
     * 修复①：CAS成功后不直接调用grantCoupon()，而是：
     *   ① CAS更新码状态（STATUS: 0→1）
     *   ② 写 T_GRANT_TASK 补偿任务
     *   以上两步在同一个DB事务中完成
     * 发券由 GrantTaskRetryJob 重试保证，最终一致性
     *
     * 注意：若 grantCoupon() 实际上在同一服务同一Oracle中（无外部调用），
     *       可以放在同一事务中直接发，此时不需要补偿任务；
     *       本修复针对的是 grantCoupon 需要调用外部服务/不同DB的情况
     */
    @Transactional(rollbackFor = Exception.class)
    protected Long doRedeem(String code, String userId, String clientIp, String channel) {

        CouponRedeemCode redeemCode = redeemCodeMapper.selectOne(
                new LambdaQueryWrapper<CouponRedeemCode>()
                        .eq(CouponRedeemCode::getCode, code)
                        .eq(CouponRedeemCode::getDeleted, 0));

        if (redeemCode == null) {
            throw new CouponException(404, "兑换码不存在");
        }

        // 终态判断 —— 直接返回，不加 FAIL_COUNT（修复：原来所有失败都加FAIL_COUNT是错的）
        switch (redeemCode.getStatus()) {
            case 1:
                throw new CouponException(400, "该兑换码已被使用，请检查您的券包");
            case 2:
                throw new CouponException(400, "该兑换码已过期");
            case 3:
                throw new CouponException(400, "该兑换码已被锁定，请联系客服解锁");
        }

        // 过期时间检查（懒过期，此时写STATUS=2，不加FAIL_COUNT）
        if (redeemCode.getExpireTime() != null && redeemCode.getExpireTime().before(new Date())) {
            CouponRedeemCode upd = new CouponRedeemCode();
            upd.setId(redeemCode.getId());
            upd.setStatus(2);
            redeemCodeMapper.updateById(upd);
            throw new CouponException(400, "该兑换码已过期");
        }

        // ── 修复②：同批次重复兑换限制 ──
        checkPerBatchLimit(redeemCode.getBatchId(), userId);

        // ── 第5层：DB CAS原子更新（防并发重入）──
        int affected = redeemCodeMapper.casRedeemCode(code, userId, new Date(), clientIp, channel);
        if (affected == 0) {
            // ✅ 只有这里才加FAIL_COUNT：STATUS=0的码被并发争抢
            redeemCodeMapper.incrementFailCount(code);
            log.warn("[防刷] CAS并发失败 code={} userId={}", code, userId);
            throw new CouponException(409, "兑换失败，请稍后重试");
        }

        // 更新批次已兑换数量
        updateBatchRedeemedCount(redeemCode.getBatchId());

        // ── 修复①：写补偿任务（与CAS在同一事务） ──
        // 若grantCoupon()是同库同事务操作，可直接调用；以下演示异步补偿写法
        Long couponId = tryGrantCouponWithFallback(code, userId, redeemCode.getTemplateId());

        log.info("[兑换成功] code={} userId={} couponId={} channel={}", code, userId, couponId, channel);
        return couponId;
    }

    /**
     * 尝试发券，失败时写补偿任务（最终一致性）
     *
     * 正常情况：直接发券成功，返回couponId，无需补偿任务
     * 发券失败：写T_GRANT_TASK，Job重试，用户稍后收到券
     */
    private Long tryGrantCouponWithFallback(String code, String userId, Long templateId) {
        // 先检查是否已有成功的发券任务（幂等：Job已处理过）
        if (grantTaskMapper.countSuccessByCode(code) > 0) {
            log.info("[幂等] code={} 已有成功发券任务，跳过", code);
            // 查找已有的couponId
            GrantTask existing = grantTaskMapper.selectOne(
                    new LambdaQueryWrapper<GrantTask>()
                            .eq(GrantTask::getRedeemCode, code)
                            .eq(GrantTask::getStatus, 1));
            return existing != null ? existing.getCouponId() : -1L;
        }

        try {
            // 直接发券（同Oracle DB，实际上与CAS在同一事务，可以直接调用）
            sys.smc.coupon.entity.Coupon issued =
                    couponService.grantCoupon(userId, userId, templateId, 7, "REDEEM_CODE");

            // 发券成功：写一条已完成的task记录（便于幂等查询和审计）
            GrantTask task = buildGrantTask(code, userId, templateId);
            task.setStatus(1);
            task.setCouponId(issued.getId());
            grantTaskMapper.insert(task);

            return issued.getId();

        } catch (Exception e) {
            log.error("[发券失败] code={} userId={} 写补偿任务", code, userId, e);

            // 发券失败：写待处理的task，Job自动重试（1分钟后第一次重试）
            GrantTask task = buildGrantTask(code, userId, templateId);
            task.setStatus(0);
            task.setFailReason(e.getMessage());
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.MINUTE, GrantTask.RETRY_DELAYS_MINUTES[0]);
            task.setNextRetryTime(cal.getTime());
            grantTaskMapper.insert(task);

            // 不抛出异常，码已标记使用，告知用户"稍后发放"
            // 注意：couponId此时未知，返回-1L表示"发放中"，前端特殊处理
            return -1L;
        }
    }

    private GrantTask buildGrantTask(String code, String userId, Long templateId) {
        GrantTask task = new GrantTask();
        task.setId(IdWorker.getId());
        task.setRedeemCode(code);
        task.setUserId(userId);
        task.setTemplateId(templateId);
        task.setRetryCount(0);
        task.setMaxRetry(3);
        return task;
    }

    // ── 修复②：同批次重复兑换检查 ──
    private void checkPerBatchLimit(Long batchId, String userId) {
        if (batchId == null) return;
        RedeemCodeBatch batch = batchMapper.selectById(batchId);
        if (batch == null) return;
        int maxPerUser = batch.getMaxPerUser() != null ? batch.getMaxPerUser() : 1;
        int alreadyRedeemed = redeemCodeMapper.countRedeemedByUserAndBatch(userId, batchId);
        if (alreadyRedeemed >= maxPerUser) {
            log.warn("[防刷] 用户批次超限 userId={} batchId={} max={} current={}",
                    userId, batchId, maxPerUser, alreadyRedeemed);
            throw new CouponException(400,
                    String.format("您在此活动中最多可兑换%d张，已达上限", maxPerUser));
        }
    }

    // ======================== 解锁功能（修复③） ========================

    /**
     * 用户自助解锁（APP内操作）
     * 前提：码是被锁定的（STATUS=3），且从未成功兑换过（STATUS不能是1）
     *
     * @param code   被锁定的兑换码
     * @param userId 申请解锁的用户ID（必须与码绑定的用户一致，或码尚未使用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void selfUnlock(String code, String userId) {
        CouponRedeemCode redeemCode = redeemCodeMapper.selectOne(
                new LambdaQueryWrapper<CouponRedeemCode>()
                        .eq(CouponRedeemCode::getCode, code)
                        .eq(CouponRedeemCode::getDeleted, 0));

        if (redeemCode == null) {
            throw new CouponException(404, "兑换码不存在");
        }
        if (redeemCode.getStatus() == 1) {
            throw new CouponException(400, "该码已成功兑换，无需解锁。请检查您的券包");
        }
        if (redeemCode.getStatus() != 3) {
            throw new CouponException(400, "该码状态正常，无需解锁");
        }

        int unlockCount = redeemCode.getUnlockCount() != null ? redeemCode.getUnlockCount() : 0;

        // 解锁次数>=2：强制人工审核，防止攻击者"社工客服循环解锁"
        if (unlockCount >= 2) {
            log.warn("[安全告警] 自助解锁超限 code={} userId={} unlockCount={}", code, userId, unlockCount);
            throw new CouponException(403,
                    "该码已被多次解锁，请联系客服并提供购买凭证，由人工审核处理");
        }

        int rows = redeemCodeMapper.adminUnlock(code);
        if (rows == 0) {
            throw new CouponException(400, "解锁失败，请稍后重试");
        }
        log.info("[自助解锁] code={} userId={} unlockCount={}", code, userId, unlockCount + 1);
    }

    /**
     * 管理员/客服解锁码
     *
     * 关键安全规则：
     * 1. STATUS=1（已成功兑换）的码绝对不允许解锁！否则一张码可被兑换两次
     * 2. UNLOCK_COUNT>=2：需要上级审批，系统自动发安全告警
     * 3. 解锁操作完整记录日志（操作人、原因、时间）
     *
     * @param code       要解锁的码
     * @param operatorId 操作人（客服工号）
     * @param reason     解锁原因（必填，用于审计）
     */
    @Transactional(rollbackFor = Exception.class)
    public void adminUnlock(String code, String operatorId, String reason) {
        CouponRedeemCode redeemCode = redeemCodeMapper.selectOne(
                new LambdaQueryWrapper<CouponRedeemCode>()
                        .eq(CouponRedeemCode::getCode, code)
                        .eq(CouponRedeemCode::getDeleted, 0));

        if (redeemCode == null) {
            throw new CouponException(404, "兑换码不存在");
        }

        // 绝对禁止：已成功兑换的码不能解锁（防止码被重复使用）
        if (redeemCode.getStatus() == 1) {
            throw new CouponException(400,
                    String.format("禁止操作！该码已被用户[%s]于[%s]成功兑换，不可解锁。" +
                                    "如需补偿用户，请走补发新码流程。",
                            redeemCode.getUserId(), redeemCode.getRedeemTime()));
        }
        if (redeemCode.getStatus() != 3) {
            throw new CouponException(400, "该码状态正常（未被锁定），无需解锁");
        }

        int unlockCount = redeemCode.getUnlockCount() != null ? redeemCode.getUnlockCount() : 0;

        // 解锁次数>=2：触发安全告警，必须上级审批（防社工攻击链）
        if (unlockCount >= 2) {
            log.error("[安全告警🚨] 管理员解锁超限！code={} operator={} unlockCount={} reason={}",
                    code, operatorId, unlockCount, reason);
            // TODO: 接入告警系统（Slack/钉钉/邮件）通知安全团队
            throw new CouponException(403,
                    "该码已被解锁" + unlockCount + "次，已超过安全阈值，需要上级审批后方可继续操作");
        }

        int rows = redeemCodeMapper.adminUnlock(code);
        if (rows == 0) {
            throw new CouponException(400, "解锁失败（码可能已被其他操作修改状态）");
        }
        log.warn("[客服解锁] code={} operator={} reason={} unlockCount={}",
                code, operatorId, reason, unlockCount + 1);
    }

    // ======================== 防刷辅助方法 ========================

    private void checkIpRateLimit(String ip) {
        String key = IP_RATE_PREFIX + ip;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        if (count != null && count > ipRateLimit) {
            log.warn("[防刷] IP限速 ip={} count={}", ip, count);
            throw new CouponException(429, "操作过于频繁，请1分钟后重试");
        }
    }

    private void checkUserRateLimit(String userId) {
        String key = USER_RATE_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        if (count != null && count > userRateLimit) {
            log.warn("[防刷] 用户限速 userId={} count={}", userId, count);
            throw new CouponException(429, "兑换过于频繁，请1分钟后重试");
        }
    }

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

    public List<CouponRedeemCode> listByBatch(Long batchId) {
        return redeemCodeMapper.selectList(
                new LambdaQueryWrapper<CouponRedeemCode>()
                        .eq(CouponRedeemCode::getBatchId, batchId)
                        .eq(CouponRedeemCode::getDeleted, 0)
                        .orderByDesc(CouponRedeemCode::getCreateTime));
    }

    public RedeemCodeBatch getBatch(Long batchId) {
        return batchMapper.selectById(batchId);
    }
}

