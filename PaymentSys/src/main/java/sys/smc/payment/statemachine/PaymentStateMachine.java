package sys.smc.payment.statemachine;

import lombok.extern.slf4j.Slf4j;
// import org.springframework.stereotype.Component;   // ← 已注释：升级到 COLA 方案后不再作为 Spring Bean 注册
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.IllegalStateTransitionException;

import javax.annotation.PostConstruct;
import java.util.*;

import static sys.smc.payment.enums.PaymentStatus.*;

/*
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  ⚠️  已停用（方案B → 方案A 升级）                                            ║
 * ║                                                                              ║
 * ║  此类为原"自研轻量状态机（方案B）"实现，已升级替换为 COLA StateMachine（方案A）。║
 * ║  代码保留作为参考和迁移对比，不再作为 Spring Bean 注册（@Component 已注释掉）。 ║
 * ║                                                                              ║
 * ║  替换关系：                                                                   ║
 * ║    旧：@Autowired PaymentStateMachine stateMachine                           ║
 * ║    新：@Autowired PaymentStateMachineService stateMachineService             ║
 * ║                                                                              ║
 * ║  接口对比（完全兼容，方法签名不变）：                                           ║
 * ║    旧：stateMachine.transition(from, to, ctx)                                ║
 * ║    新：stateMachineService.transition(from, to, ctx)    ← 签名完全相同        ║
 * ║                                                                              ║
 * ║  新文件：                                                                     ║
 * ║    PaymentEvent.java             — COLA 事件枚举                             ║
 * ║    PaymentStateMachineConfig.java — COLA @Bean 配置，注册所有合法转换          ║
 * ║    PaymentStateMachineService.java — 服务封装，对外提供 transition 接口        ║
 * ║                                                                              ║
 * ║  升级原因：                                                                   ║
 * ║    1. COLA DSL 更清晰，可生成 PlantUML 状态图（generatePlantUML()）           ║
 * ║    2. Alibaba/蚂蚁/钉钉等亿级系统验证，社区活跃                               ║
 * ║    3. 无状态设计与自研方案相同，无实例管理负担                                  ║
 * ║    4. 团队扩张后可无缝迁移                                                    ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 */

/**
 * 自研轻量支付状态机（方案B）— 已停用，请使用 PaymentStateMachineService（方案A COLA）
 *
 * @deprecated 已升级为 COLA StateMachine（方案A），见 PaymentStateMachineService
 */
@Deprecated
// @Component  // ← 已注释：升级到 COLA 后此 Bean 不再注册，避免与 PaymentStateMachineService 冲突
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
