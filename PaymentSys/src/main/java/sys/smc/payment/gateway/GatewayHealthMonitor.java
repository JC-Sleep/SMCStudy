package sys.smc.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import sys.smc.payment.enums.PaymentChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 支付网关健康监控与熔断器
 *
 * ─── 设计说明 ────────────────────────────────────────────────────────────────
 *
 * 电讯行业的路由价值不是"成本路由"（哪个渠道便宜走哪个），
 * 而是"故障熔断"：月初账单日 CyberSource 突然 503，几千张信用卡扣款全部失败。
 * 熔断器在连续 N 次失败后快速返回错误 + 告警，避免服务器线程耗尽。
 *
 * ─── 三态状态机 ──────────────────────────────────────────────────────────────
 *
 *   CLOSED ──(连续失败 >= THRESHOLD)──▶ OPEN ──(超时 RECOVERY_TIMEOUT_MS)──▶ HALF_OPEN
 *     ▲                                                                         │
 *     └───────────────── (探测成功 recordSuccess) ──────────────────────────────┘
 *                                 │
 *     OPEN ◀─── (探测失败 recordFailure) ──────────────────────────────────────┘
 *
 * ─── 线程安全保证 ────────────────────────────────────────────────────────────
 *
 * 1. failureCounts: ConcurrentHashMap<Channel, AtomicInteger>
 *    - computeIfAbsent 保证 AtomicInteger 始终在 map 中（getOrDefault 会返回临时对象，.set(0) 丢失）
 *
 * 2. circuitOpenTime: ConcurrentHashMap<Channel, Long>
 *    - null 表示 CLOSED，非 null 表示 OPEN/HALF_OPEN
 *    - putIfAbsent 保证只有第一个到达阈值的线程打开熔断（幂等）
 *    - remove(key, value) CAS 保证 HALF_OPEN 探测机会只有一个线程能拿到
 *
 * 原子性保证："状态机校验→DB写入" 仍靠调用方（@Transactional + 乐观锁），
 * 此类仅提供"是否允许发起请求"的判断层。
 */
@Component
@Slf4j
public class GatewayHealthMonitor {

    /**
     * 连续失败计数
     *
     * Bug fix（与原 plan 对比）：使用 computeIfAbsent 而非 getOrDefault。
     * getOrDefault 若 key 不存在会返回一个新的临时 AtomicInteger，
     * 对它调用 .set(0) 不会影响 map 中的任何对象，导致重置操作静默丢失。
     */
    private final Map<PaymentChannel, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    /**
     * 熔断开启时间戳（毫秒）
     *
     * null  → CLOSED（正常）
     * 非 null → OPEN（熔断中）或 HALF_OPEN（即将移除，允许一次探测）
     */
    private final Map<PaymentChannel, Long> circuitOpenTime = new ConcurrentHashMap<>();

    /** 连续失败次数阈值，达到后触发熔断 */
    private static final int FAILURE_THRESHOLD = 3;

    /** 熔断后等待恢复的时间（60 秒后进入半开探测） */
    private static final long RECOVERY_TIMEOUT_MS = 60_000L;

    // ─── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 记录某渠道一次成功请求（重置失败计数 + 关闭熔断）
     *
     * 在以下时机调用：
     *   - 支付发起成功（网关返回创建成功）
     *   - 回调处理成功（签名验证通过并正常处理）
     */
    public void recordSuccess(PaymentChannel channel) {
        // computeIfAbsent 确保 AtomicInteger 在 map 中，.set(0) 才有效
        failureCounts.computeIfAbsent(channel, k -> new AtomicInteger(0)).set(0);
        Long removedAt = circuitOpenTime.remove(channel);
        if (removedAt != null) {
            log.info("[熔断恢复] 渠道 {} 已恢复正常（探测成功）", channel.getCode());
        }
    }

    /**
     * 记录某渠道一次失败请求（累加计数，达阈值时熔断）
     *
     * 在以下时机调用：
     *   - 支付请求发起失败（网关超时/5xx）
     *   - 对账查询网关状态失败
     *
     * 注意：签名验证失败、业务拒绝（卡被拒）等不代表网关不可用，不应调用此方法。
     */
    public void recordFailure(PaymentChannel channel) {
        int count = failureCounts.computeIfAbsent(channel, k -> new AtomicInteger(0))
                                 .incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            // putIfAbsent 幂等：只有第一个到达阈值的调用真正打开熔断，避免重复覆盖开启时间
            Long prev = circuitOpenTime.putIfAbsent(channel, System.currentTimeMillis());
            if (prev == null) {
                log.error("[熔断触发] 渠道 {} 连续失败 {} 次，熔断已开启！{}ms 后进入半开探测。",
                        channel.getCode(), count, RECOVERY_TIMEOUT_MS);
                // TODO: 接入告警（钉钉/邮件/Prometheus 指标）
                // alertService.sendCircuitBreakerAlert(channel, count);
            }
        }
    }

    /**
     * 判断熔断是否开启（线程安全）
     *
     * 返回值含义：
     *   true  → 熔断中，拒绝请求（OPEN 状态）
     *   false → 允许请求（CLOSED 状态或 HALF_OPEN 探测窗口）
     *
     * Bug fix（与原 plan 对比）：
     *   原计划用 Boolean circuitOpen（可见性问题）+ 直接 put(false)（并发竞争）。
     *   修复：用 Long circuitOpenTime 作唯一真相，HALF_OPEN 转换使用
     *   ConcurrentHashMap.remove(k, v) CAS 保证只有一个线程拿到探测机会。
     */
    public boolean isCircuitOpen(PaymentChannel channel) {
        Long openTime = circuitOpenTime.get(channel);
        if (openTime == null) {
            return false; // CLOSED
        }
        if (System.currentTimeMillis() - openTime > RECOVERY_TIMEOUT_MS) {
            // 超时，尝试 CAS: 仅一个线程能成功 remove(channel, openTime)，获得探测机会
            boolean probeGranted = circuitOpenTime.remove(channel, openTime);
            if (probeGranted) {
                log.info("[熔断半开] 渠道 {} 进入半开状态，允许一次探测请求（成功则恢复，失败则重新熔断）",
                        channel.getCode());
            }
            // 无论是否拿到探测机会，都放行这次请求（失败时 recordFailure 会重新熔断）
            return false;
        }
        return true; // OPEN，拒绝请求
    }

    /**
     * 获取渠道健康状态快照（监控/运维接口用）
     *
     * 注意：此方法为纯读取，不触发半开状态转换（区别于 isCircuitOpen）
     */
    public Map<String, Object> getChannelStatus(PaymentChannel channel) {
        Long openTime = circuitOpenTime.get(channel);
        boolean circuitOpen = openTime != null
                && System.currentTimeMillis() - openTime <= RECOVERY_TIMEOUT_MS;

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("channel", channel.getCode());
        status.put("channelName", channel.getName());
        status.put("circuitOpen", circuitOpen);
        status.put("failureCount", failureCounts.getOrDefault(channel, new AtomicInteger(0)).get());
        status.put("circuitOpenSince", openTime);
        status.put("failureThreshold", FAILURE_THRESHOLD);
        status.put("recoveryTimeoutMs", RECOVERY_TIMEOUT_MS);
        return status;
    }

    /**
     * 获取所有渠道状态概览
     */
    public Map<String, Object> getAllChannelStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (PaymentChannel channel : PaymentChannel.values()) {
            result.put(channel.getCode(), getChannelStatus(channel));
        }
        return result;
    }
}

