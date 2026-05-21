package sys.smc.payment.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.IllegalStateTransitionException;

import javax.annotation.PostConstruct;
import java.util.*;

import static sys.smc.payment.enums.PaymentStatus.*;

/**
 * 自研轻量支付状态机（方案B）
 *
 * ─── 设计原则 ────────────────────────────────────────────────────
 *  1. 无状态：此组件不持有任何交易的当前状态，状态真相来源在 DB
 *  2. 白名单机制：只有明确声明的转换才合法，未声明的全部拒绝
 *  3. 线程安全：transitions Map 在 @PostConstruct 后只读，并发安全
 *  4. 原子性保障：状态机本身只做"校验"，DB 写入由调用方在同一事务/乐观锁保护下完成
 *
 * ─── 合法状态迁移白名单 ──────────────────────────────────────────
 *
 *  正常支付流程：
 *    INIT          → PENDING          [SUBMIT]
 *    PENDING       → SUCCESS          [BANK_CONFIRM, guard: 签名有效]
 *    PENDING       → FAILED           [BANK_DECLINE]
 *    PENDING       → TIMEOUT          [SYSTEM_TIMEOUT]
 *
 *  对账修正流程（TIMEOUT 禁止直接→SUCCESS，必须经过 RECONCILING 留审计踪迹）：
 *    TIMEOUT       → RECONCILING      [RECONCILE_START]
 *    RECONCILING   → SUCCESS          [RECON_SUCCESS]
 *    RECONCILING   → FAILED           [RECON_FAIL]
 *
 *  退款流程：
 *    SUCCESS          → REFUNDING     [REFUND_APPLY]
 *    PARTIALLY_REFUNDED → REFUNDING   [REFUND_APPLY]  ← 继续部分退款
 *    REFUND_FAILED    → REFUNDING     [REFUND_APPLY]  ← 退款失败重试
 *    REFUNDING     → REFUNDED         [REFUND_COMPLETE] 全额退款终态
 *    REFUNDING     → PARTIALLY_REFUNDED [PARTIAL_REFUND]
 *    REFUNDING     → REFUND_FAILED    [REFUND_FAIL] 需人工干预
 *
 * ─── 非法转换示例（自动拦截）──────────────────────────────────────
 *    TIMEOUT   → SUCCESS    ❌ 银行迟到回调必须走对账修正，不可绕过
 *    FAILED    → REFUNDED   ❌ 失败的订单不能退款
 *    SUCCESS   → SUCCESS    ❌ 防止重复回调处理
 *    REFUNDED  → REFUNDING  ❌ 已终态不能再退
 */
@Component
@Slf4j
public class PaymentStateMachine {

    /** Key: 源状态, Value: 从该状态出发的所有合法转换列表 */
    private final Map<PaymentStatus, List<PaymentTransition>> transitions = new EnumMap<>(PaymentStatus.class);

    @PostConstruct
    public void init() {
        // ─── 正常支付流程 ────────────────────────────────────────────────
        register(INIT, PENDING, "提交至银行（INIT→PENDING）");

        register(PENDING, SUCCESS, "银行确认成功（PENDING→SUCCESS）",
                ctx -> ctx.isSignatureValid(),   // guard：签名必须有效才能标记为SUCCESS
                ctx -> log.info("[SM] 支付成功 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(PENDING, FAILED, "银行拒绝扣款（PENDING→FAILED）",
                null,
                ctx -> log.warn("[SM] 银行拒绝支付 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(PENDING, TIMEOUT, "系统超时未收到银行回调（PENDING→TIMEOUT）");

        // ─── 对账修正流程 ─────────────────────────────────────────────────
        // 关键安全约束：TIMEOUT 不得直接 → SUCCESS，必须先进 RECONCILING 留下审计记录
        register(TIMEOUT, RECONCILING, "对账Job介入，进入核查中间态（TIMEOUT→RECONCILING）",
                null,
                ctx -> log.warn("[SM][对账] 进入对账核查状态 txn={} operator={} remark={}",
                        txnId(ctx), ctx.getOperator(), ctx.getRemark()));

        register(RECONCILING, SUCCESS, "对账确认银行已扣款（RECONCILING→SUCCESS）",
                null,
                ctx -> log.warn("[SM][对账修正] ⚠️ 对账修正为SUCCESS txn={} operator={} remark={}",
                        txnId(ctx), ctx.getOperator(), ctx.getRemark()));

        register(RECONCILING, FAILED, "对账确认银行未扣款（RECONCILING→FAILED）",
                null,
                ctx -> log.warn("[SM][对账修正] 对账确认FAILED txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        // ─── 退款流程 ──────────────────────────────────────────────────────
        register(SUCCESS, REFUNDING, "退款申请通过，进入退款处理中（SUCCESS→REFUNDING）",
                null,
                ctx -> log.info("[SM] 发起退款 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(PARTIALLY_REFUNDED, REFUNDING, "继续申请部分退款（PARTIALLY_REFUNDED→REFUNDING）",
                null,
                ctx -> log.info("[SM] 继续部分退款 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(REFUND_FAILED, REFUNDING, "退款失败重试（REFUND_FAILED→REFUNDING）",
                null,
                ctx -> log.warn("[SM] 退款失败重试 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(REFUNDING, REFUNDED, "全额退款完成（REFUNDING→REFUNDED）",
                null,
                ctx -> log.info("[SM] ✅ 全额退款完成 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(REFUNDING, PARTIALLY_REFUNDED, "部分退款完成（REFUNDING→PARTIALLY_REFUNDED）",
                null,
                ctx -> log.info("[SM] 部分退款完成 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        register(REFUNDING, REFUND_FAILED, "退款执行失败，需人工干预（REFUNDING→REFUND_FAILED）",
                null,
                ctx -> log.error("[SM] ❌ 退款失败！需人工核查 txn={} operator={}",
                        txnId(ctx), ctx.getOperator()));

        int totalTransitions = transitions.values().stream().mapToInt(List::size).sum();
        log.info("[支付状态机] 初始化完成，共注册 {} 条合法状态转换", totalTransitions);
    }

    /**
     * 执行状态转换（核心方法）
     *
     * ─── 原子性保障 ──────────────────────────────────────────────────────────
     * 本方法仅做"内存校验"，不写 DB。
     * 调用方必须确保：
     *   1. 在 @Transactional 方法中调用本方法，且
     *   2. 紧接着使用 entity.setVersion(transaction.getVersion()) + updateById(entity)
     *
     * 这样，若其他线程在"本方法返回"和"updateById"之间修改了 DB，
     * MyBatis-Plus 的乐观锁（WHERE version=V）会返回 0 行，触发 OptimisticLockException。
     * ────────────────────────────────────────────────────────────────────────
     *
     * @param from 当前状态（从 DB 读取，不能是内存中的过期值）
     * @param to   目标状态
     * @param ctx  上下文（含交易对象、操作人等）
     * @throws IllegalStateTransitionException 转换不在白名单中，或 guard 条件不满足时抛出
     */
    public void transition(PaymentStatus from, PaymentStatus to, TransitionContext ctx) {
        List<PaymentTransition> available = transitions.getOrDefault(from, Collections.emptyList());

        // 白名单查找：必须显式定义才合法
        Optional<PaymentTransition> matched = available.stream()
                .filter(t -> t.getTo() == to)
                .findFirst();

        if (!matched.isPresent()) {
            log.error("[SM] 非法状态转换拒绝: {} → {} txn={} 合法目标: {}",
                    from, to, txnId(ctx), getAvailableTargets(from));
            throw new IllegalStateTransitionException(from.name(), to.name());
        }

        PaymentTransition t = matched.get();

        // Guard 条件校验
        if (t.getGuard() != null && !t.getGuard().test(ctx)) {
            log.error("[SM] 状态转换 Guard 校验失败: {} → {} txn={} desc={}",
                    from, to, txnId(ctx), t.getDescription());
            throw new IllegalStateTransitionException(from.name(), to.name(),
                    "前置条件不满足: " + t.getDescription());
        }

        // 执行 Action（日志/通知，不写 DB）
        if (t.getAction() != null) {
            t.getAction().accept(ctx);
        }

        log.debug("[SM] 转换通过校验: {} → {} ({}) txn={}",
                from, to, t.getDescription(), txnId(ctx));
    }

    /**
     * 检查转换是否在白名单中（不校验 guard，不执行 action，不抛异常）
     * 用于前端预校验或条件判断
     */
    public boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return transitions.getOrDefault(from, Collections.emptyList())
                .stream()
                .anyMatch(t -> t.getTo() == to);
    }

    /**
     * 获取某状态的所有合法目标状态（调试/日志用）
     */
    public Set<PaymentStatus> getAvailableTargets(PaymentStatus from) {
        Set<PaymentStatus> result = new LinkedHashSet<>();
        transitions.getOrDefault(from, Collections.emptyList())
                .forEach(t -> result.add(t.getTo()));
        return result;
    }

    // ─── 私有辅助方法 ──────────────────────────────────────────────────────

    /** 注册无 guard 无 action 的转换 */
    private void register(PaymentStatus from, PaymentStatus to, String desc) {
        register(from, to, desc, null, null);
    }

    /** 注册完整转换定义 */
    private void register(PaymentStatus from, PaymentStatus to, String desc,
                          java.util.function.Predicate<TransitionContext> guard,
                          java.util.function.Consumer<TransitionContext> action) {
        transitions.computeIfAbsent(from, k -> new ArrayList<>())
                .add(PaymentTransition.builder()
                        .from(from)
                        .to(to)
                        .description(desc)
                        .guard(guard)
                        .action(action)
                        .build());
    }

    /** 安全取交易ID用于日志（防 NPE） */
    private String txnId(TransitionContext ctx) {
        if (ctx == null || ctx.getTransaction() == null) return "N/A";
        return ctx.getTransaction().getTransactionId();
    }
}

