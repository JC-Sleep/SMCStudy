package sys.smc.payment.statemachine;

import com.alibaba.cola.statemachine.StateMachine;
import com.alibaba.cola.statemachine.StateMachineBuilder;
import com.alibaba.cola.statemachine.StateMachineBuilderFactory;
import com.alibaba.cola.statemachine.StateMachineFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sys.smc.payment.enums.PaymentStatus;

import static sys.smc.payment.enums.PaymentStatus.*;
import static sys.smc.payment.statemachine.PaymentEvent.*;

/**
 * COLA StateMachine 配置类（方案A — 升级自方案B自研状态机）
 *
 * ─── 核心设计理念 ─────────────────────────────────────────────────────────────────
 *  COLA StateMachine 是"无状态校验器"，不是"有状态机器实例"：
 *    - 你每次传入：当前状态（从DB读） + 触发事件 + 上下文
 *    - 它返回：目标状态（你负责写DB）
 *    - 没有实例管理，并发1000笔只用1个 StateMachine Bean，内存友好
 *
 * ─── 原子性保障（不变，与方案B相同）────────────────────────────────────────────────
 *  COLA 只做"内存校验+action执行"，不写 DB。
 *  调用方（PaymentStateMachineService.transition）必须确保：
 *    1. 在 @Transactional 方法中调用
 *    2. 紧接着以 @Version 乐观锁执行 updateById
 *  这样"校验通过→写DB"是原子的，无多线程竞争窗口。
 *
 * ─── COLA 未定义转换的行为 ────────────────────────────────────────────────────────
 *  当 fireEvent(currentState, event) 找不到对应转换时，COLA 返回原状态（不抛异常）。
 *  PaymentStateMachineService 封装层检测 result == from 并抛 IllegalStateTransitionException。
 *
 * ─── 防重复注册（Test 环境保护）──────────────────────────────────────────────────
 *  COLA 将 StateMachine 注册到 static Map。Spring 测试多次启动时可能重复注册。
 *  此处在 build 前先尝试从工厂获取，已存在则复用，避免 StateMachineException。
 *
 * @see sys.smc.payment.statemachine.PaymentEvent              事件枚举
 * @see sys.smc.payment.statemachine.PaymentStateMachineService 对外服务封装（提供 transition(from, to, ctx) 接口）
 */
@Configuration
@Slf4j
public class PaymentStateMachineConfig {

    /** COLA StateMachine 注册 ID，在整个 JVM 内全局唯一 */
    public static final String MACHINE_ID = "paymentStateMachine";

    @Bean
    public StateMachine<PaymentStatus, PaymentEvent, TransitionContext> paymentStateMachine() {

        // ── 防止 Spring Test 重启导致重复注册 ──────────────────────────────────────
        // COLA 用 static Map 存储状态机，JVM 生命周期内只注册一次。
        // 如果已注册，直接复用，避免 "already exists" 异常。
        try {
            StateMachine<PaymentStatus, PaymentEvent, TransitionContext> existing =
                    StateMachineFactory.get(MACHINE_ID);
            if (existing != null) {
                log.info("[COLA状态机] 复用已注册的状态机: {}", MACHINE_ID);
                return existing;
            }
        } catch (Exception ignored) {
            // 正常路径：首次注册时 get() 会抛异常（"not found"），继续创建
        }

        StateMachineBuilder<PaymentStatus, PaymentEvent, TransitionContext> builder =
                StateMachineBuilderFactory.create();

        // ── 正常支付流程 ──────────────────────────────────────────────────────────

        // INIT → PENDING：提交支付到银行
        builder.externalTransition()
                .from(INIT).to(PENDING).on(SUBMIT)
                .perform((from, to, event, ctx) ->
                        log.info("[SM-COLA] INIT→PENDING txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // PENDING → SUCCESS：银行回调确认成功
        // ⚠️ guard：signatureValid 必须为 true（防止签名无效的回调将交易标记为成功）
        // ⚠️ guard 失败时，COLA 返回原状态（PENDING），PaymentStateMachineService 检测后抛异常
        builder.externalTransition()
                .from(PENDING).to(SUCCESS).on(BANK_CONFIRM)
                .when(ctx -> ctx.isSignatureValid())
                .perform((from, to, event, ctx) ->
                        log.info("[SM-COLA] 支付成功 PENDING→SUCCESS txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // PENDING → FAILED：银行拒绝扣款
        builder.externalTransition()
                .from(PENDING).to(FAILED).on(BANK_DECLINE)
                .perform((from, to, event, ctx) ->
                        log.warn("[SM-COLA] 银行拒绝 PENDING→FAILED txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // PENDING → TIMEOUT：系统超时未收到银行回调
        builder.externalTransition()
                .from(PENDING).to(TIMEOUT).on(SYSTEM_TIMEOUT)
                .perform((from, to, event, ctx) ->
                        log.warn("[SM-COLA] 系统超时 PENDING→TIMEOUT txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // ── 对账修正流程 ──────────────────────────────────────────────────────────
        //
        // 关键安全约束：TIMEOUT 禁止直接 → SUCCESS（状态机未定义此转换，自动拦截）
        // 必须经过 RECONCILING 中间态，确保"系统曾经超时"这一历史事实有审计记录。
        // （银行迟到回调绑过对账流程的漏洞已被此设计封堵）

        // TIMEOUT → RECONCILING：对账 Job 介入，进入核查中间态
        builder.externalTransition()
                .from(TIMEOUT).to(RECONCILING).on(RECONCILE_START)
                .perform((from, to, event, ctx) ->
                        log.warn("[SM-COLA][对账] 进入核查中间态 TIMEOUT→RECONCILING txn={} operator={} remark={}",
                                txnId(ctx), ctx.getOperator(), ctx.getRemark()));

        // RECONCILING → SUCCESS：对账确认银行已扣款
        builder.externalTransition()
                .from(RECONCILING).to(SUCCESS).on(RECON_SUCCESS)
                .perform((from, to, event, ctx) ->
                        log.warn("[SM-COLA][对账修正] ⚠️ 对账修正为SUCCESS RECONCILING→SUCCESS txn={} operator={} remark={}",
                                txnId(ctx), ctx.getOperator(), ctx.getRemark()));

        // RECONCILING → FAILED：对账确认银行未扣款
        builder.externalTransition()
                .from(RECONCILING).to(FAILED).on(RECON_FAIL)
                .perform((from, to, event, ctx) ->
                        log.warn("[SM-COLA][对账修正] 对账确认FAILED RECONCILING→FAILED txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // ── 退款流程 ──────────────────────────────────────────────────────────────
        //
        // 三种源状态都可以触发 REFUND_APPLY 事件进入 REFUNDING：
        //   SUCCESS          → 正常全额/部分退款
        //   PARTIALLY_REFUNDED → 继续部分退款
        //   REFUND_FAILED    → 退款失败重试
        //
        // 注意：FAILED → REFUNDING 被禁止（失败的订单不能退款，状态机未定义此路径）
        //       REFUNDED → REFUNDING 被禁止（终态，不可逆转）
        builder.externalTransitions()
                .fromAmong(SUCCESS, PARTIALLY_REFUNDED, REFUND_FAILED)
                .to(REFUNDING).on(REFUND_APPLY)
                .perform((from, to, event, ctx) ->
                        log.info("[SM-COLA] 发起退款 {}→REFUNDING txn={} operator={}",
                                from, txnId(ctx), ctx.getOperator()));

        // REFUNDING → REFUNDED：全额退款完成（终态）
        builder.externalTransition()
                .from(REFUNDING).to(REFUNDED).on(REFUND_COMPLETE)
                .perform((from, to, event, ctx) ->
                        log.info("[SM-COLA] ✅ 全额退款完成 REFUNDING→REFUNDED txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // REFUNDING → PARTIALLY_REFUNDED：部分退款完成（可再次发起退款）
        builder.externalTransition()
                .from(REFUNDING).to(PARTIALLY_REFUNDED).on(PARTIAL_REFUND)
                .perform((from, to, event, ctx) ->
                        log.info("[SM-COLA] 部分退款完成 REFUNDING→PARTIALLY_REFUNDED txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // REFUNDING → REFUND_FAILED：退款执行失败，需人工干预
        builder.externalTransition()
                .from(REFUNDING).to(REFUND_FAILED).on(REFUND_FAIL)
                .perform((from, to, event, ctx) ->
                        log.error("[SM-COLA] ❌ 退款失败！需人工核查 REFUNDING→REFUND_FAILED txn={} operator={}",
                                txnId(ctx), ctx.getOperator()));

        // ── 所有未定义转换自动被拒绝 ─────────────────────────────────────────────
        // COLA：未定义的 (from, event) 组合，fireEvent 返回原状态（不抛异常）。
        // PaymentStateMachineService 封装层检测 result == from 并抛 IllegalStateTransitionException。
        // 例如：
        //   TIMEOUT → SUCCESS    ❌（无 TIMEOUT + BANK_CONFIRM 定义）
        //   FAILED  → REFUNDED   ❌（无 FAILED  + REFUND_APPLY 定义，因 fromAmong 不含 FAILED）
        //   SUCCESS → SUCCESS    ❌（无 SUCCESS + BANK_CONFIRM 定义）
        //   REFUNDED→ REFUNDING  ❌（无 REFUNDED+ REFUND_APPLY 定义）

        StateMachine<PaymentStatus, PaymentEvent, TransitionContext> machine = builder.build(MACHINE_ID);
        log.info("[COLA状态机] 初始化完成，machineId={}，共配置 {} 个事件触发路径",
                MACHINE_ID, PaymentEvent.values().length);
        return machine;
    }

    /** 安全取交易ID用于日志（防 NPE） */
    private String txnId(TransitionContext ctx) {
        if (ctx == null || ctx.getTransaction() == null) return "N/A";
        return ctx.getTransaction().getTransactionId();
    }
}


