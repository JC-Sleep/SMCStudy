package sys.smc.payment.statemachine;

import com.alibaba.cola.statemachine.StateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.IllegalStateTransitionException;

import java.util.LinkedHashSet;
import java.util.Set;

import static sys.smc.payment.enums.PaymentStatus.*;

/**
 * COLA StateMachine 服务封装
 *
 * ─── 职责 ─────────────────────────────────────────────────────────────────────
 *  1. 提供与自研状态机（PaymentStateMachine，方案B）完全相同的 transition(from, to, ctx) 接口，
 *     使得 PaymentCallbackServiceEnhanced / ReconciliationProcessor 等调用方只需替换注入类型，
 *     无需修改业务逻辑。
 *
 *  2. 内部将 (from, to) 状态对映射为 COLA 所需的 PaymentEvent，调用 COLA fireEvent，
 *     并将 COLA"未定义转换时返回原状态"的行为转换为 IllegalStateTransitionException。
 *
 *  3. 暴露 canTransition / getAvailableTargets，保持与自研状态机相同的调试接口。
 *
 * ─── 原子性保障（与方案B完全相同，不变）────────────────────────────────────────
 *  本类仅做"内存校验 + action 执行"，不写 DB。
 *  调用方必须确保：
 *    1. 在 @Transactional 方法中调用 transition()，
 *    2. 紧接着以 @Version 乐观锁执行 updateById(entity)。
 *  这样"校验通过 → 写DB"在同一事务内，无多线程竞争窗口（乐观锁兜底）。
 *
 * ─── COLA fireEvent 返回语义 ──────────────────────────────────────────────────
 *  成功转换  : fireEvent 返回 targetState（≠ from）
 *  未定义转换: fireEvent 返回 from（COLA 不抛异常）→ 本类检测并抛 IllegalStateTransitionException
 *  guard 失败: fireEvent 返回 from（COLA 不抛异常）→ 本类检测并抛 IllegalStateTransitionException
 *
 * @see PaymentStateMachineConfig  COLA Bean 定义与转换注册
 * @see PaymentEvent               事件枚举
 */
@Service
@Slf4j
public class PaymentStateMachineService {

    /** COLA 无状态状态机 Bean，由 PaymentStateMachineConfig 注册 */
    @Autowired
    private StateMachine<PaymentStatus, PaymentEvent, TransitionContext> paymentStateMachine;

    /**
     * 执行状态转换（核心方法，接口与自研状态机 PaymentStateMachine.transition 完全一致）
     *
     * ─── 原子性边界 ──────────────────────────────────────────────────────────────
     * 调用方必须在同一个 @Transactional 方法中调用本方法，并在方法返回后立即执行乐观锁 DB 写入：
     *
     *   @Transactional(propagation = REQUIRES_NEW)
     *   public void doUpdate(...) {
     *       // 1. 从 DB 读取最新 version（避免 stale read）
     *       PaymentTransaction fresh = transactionMapper.selectById(id);
     *       // 2. 状态机校验（内存，不写DB）
     *       stateMachineService.transition(from, to, ctx);
     *       // 3. 乐观锁写DB（WHERE version = fresh.getVersion()）
     *       int rows = transactionMapper.updateById(update);
     *       if (rows == 0) throw new OptimisticLockException(...);
     *   }
     *
     * ─── 内部流程 ──────────────────────────────────────────────────────────────
     *  step1: 将 (from, to) 映射到 PaymentEvent（映射表见 resolveEvent）
     *         若映射不到 → 直接抛 IllegalStateTransitionException（不调用 COLA，更快）
     *  step2: 调用 COLA paymentStateMachine.fireEvent(from, event, ctx)
     *         COLA 内部执行 guard 校验 + 转换 action（日志等）
     *  step3: 检查 COLA 返回值
     *         返回 to（≠ from）→ 转换成功，返回
     *         返回 from       → guard 失败或未定义，抛 IllegalStateTransitionException
     *
     * @param from 当前状态（必须从 DB 最新读取，不能是内存中的过期缓存）
     * @param to   目标状态
     * @param ctx  转换上下文（含 transaction、operator、signatureValid 等）
     * @throws IllegalStateTransitionException 转换不合法（不在白名单）或 guard 条件不满足
     */
    public void transition(PaymentStatus from, PaymentStatus to, TransitionContext ctx) {

        // Step 1: (from, to) → PaymentEvent
        PaymentEvent event = resolveEvent(from, to);
        if (event == null) {
            // (from, to) 对不在任何已定义的转换路径中，无需调用 COLA 直接拒绝
            log.error("[SM-COLA] 非法状态转换拒绝（无对应事件定义）: {} → {} txn={} 合法目标: {}",
                    from, to, txnId(ctx), getAvailableTargets(from));
            throw new IllegalStateTransitionException(from.name(), to.name());
        }

        log.debug("[SM-COLA] 尝试转换: {} -[{}]→ {} txn={}", from, event, to, txnId(ctx));

        // Step 2: COLA fireEvent 执行 guard 校验 + action
        // COLA 是无状态的：不持有交易状态，每次入参都是全量信息
        PaymentStatus result = paymentStateMachine.fireEvent(from, event, ctx);

        // Step 3: 检测 COLA 返回值
        // result == from 有两种情况：
        //   a) guard 失败（如 BANK_CONFIRM 的 signatureValid=false）
        //   b) 转换未定义（理论上已被 step1 的 resolveEvent 过滤，此处是双重保险）
        if (result == from) {
            // 对 BANK_CONFIRM 单独提示签名信息，方便排查
            if (event == PaymentEvent.BANK_CONFIRM) {
                log.error("[SM-COLA] Guard校验失败（签名无效），转换被拒绝: {} → {} txn={}",
                        from, to, txnId(ctx));
                throw new IllegalStateTransitionException(from.name(), to.name(),
                        "前置条件不满足：签名验证失败（SignatureValid=false）");
            }
            log.error("[SM-COLA] COLA 未执行转换（guard失败或未定义）: {} -[{}]→ {} txn={}",
                    from, event, to, txnId(ctx));
            throw new IllegalStateTransitionException(from.name(), to.name());
        }

        log.debug("[SM-COLA] 转换通过校验: {} → {} txn={}", from, result, txnId(ctx));
    }

    /**
     * 检查 (from, to) 转换是否合法（不校验 guard，不执行 action，不抛异常）
     * 用于前端预校验或条件判断，与自研状态机 canTransition 接口一致。
     *
     * 注意：此处只检查白名单（event 映射是否存在），不执行 guard 校验（如签名校验）。
     * 真正的 guard 校验只在 transition() 时执行。
     */
    public boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return resolveEvent(from, to) != null;
    }

    /**
     * 获取某状态的所有合法目标状态（调试/日志用）
     * 与自研状态机 getAvailableTargets 接口一致。
     */
    public Set<PaymentStatus> getAvailableTargets(PaymentStatus from) {
        Set<PaymentStatus> result = new LinkedHashSet<>();
        for (PaymentStatus to : PaymentStatus.values()) {
            if (resolveEvent(from, to) != null) {
                result.add(to);
            }
        }
        return result;
    }

    // ─── 内部辅助方法 ─────────────────────────────────────────────────────────────

    /**
     * 将 (from, to) 状态对映射到 PaymentEvent
     *
     * 本映射表是状态机白名单的"索引层"：
     *   - resolveEvent 返回 null  → 非法转换，直接拒绝（不调用 COLA）
     *   - resolveEvent 返回 event → 合法候选，交由 COLA 做 guard 最终裁决
     *
     * 所有合法路径（与 PaymentStateMachineConfig 中的 builder 注册完全对应）：
     *   INIT           + PENDING          = SUBMIT
     *   PENDING        + SUCCESS          = BANK_CONFIRM    (guard: signatureValid)
     *   PENDING        + FAILED           = BANK_DECLINE
     *   PENDING        + TIMEOUT          = SYSTEM_TIMEOUT
     *   TIMEOUT        + RECONCILING      = RECONCILE_START
     *   RECONCILING    + SUCCESS          = RECON_SUCCESS
     *   RECONCILING    + FAILED           = RECON_FAIL
     *   SUCCESS        + REFUNDING        = REFUND_APPLY
     *   PARTIALLY_REFUNDED + REFUNDING    = REFUND_APPLY
     *   REFUND_FAILED  + REFUNDING        = REFUND_APPLY
     *   REFUNDING      + REFUNDED         = REFUND_COMPLETE
     *   REFUNDING      + PARTIALLY_REFUNDED = PARTIAL_REFUND
     *   REFUNDING      + REFUND_FAILED    = REFUND_FAIL
     */
    private PaymentEvent resolveEvent(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) {
            return null;
        }
        switch (from) {
            case INIT:
                if (to == PENDING)             return PaymentEvent.SUBMIT;
                break;
            case PENDING:
                if (to == SUCCESS)             return PaymentEvent.BANK_CONFIRM;
                if (to == FAILED)              return PaymentEvent.BANK_DECLINE;
                if (to == TIMEOUT)             return PaymentEvent.SYSTEM_TIMEOUT;
                break;
            case TIMEOUT:
                if (to == RECONCILING)         return PaymentEvent.RECONCILE_START;
                break;
            case RECONCILING:
                if (to == SUCCESS)             return PaymentEvent.RECON_SUCCESS;
                if (to == FAILED)              return PaymentEvent.RECON_FAIL;
                break;
            case SUCCESS:
            case PARTIALLY_REFUNDED:
            case REFUND_FAILED:
                // 三种源状态都通过同一个 REFUND_APPLY 事件进入 REFUNDING
                if (to == REFUNDING)           return PaymentEvent.REFUND_APPLY;
                break;
            case REFUNDING:
                if (to == REFUNDED)            return PaymentEvent.REFUND_COMPLETE;
                if (to == PARTIALLY_REFUNDED)  return PaymentEvent.PARTIAL_REFUND;
                if (to == REFUND_FAILED)       return PaymentEvent.REFUND_FAIL;
                break;
            default:
                break;
        }
        return null; // 非法转换（from→to 不在任何白名单路径中）
    }

    /** 安全取交易ID用于日志（防 NPE） */
    private String txnId(TransitionContext ctx) {
        if (ctx == null || ctx.getTransaction() == null) return "N/A";
        return ctx.getTransaction().getTransactionId();
    }
}

