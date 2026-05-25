package sys.smc.payment.statemachine;

import lombok.Builder;
import lombok.Data;
import sys.smc.payment.enums.PaymentStatus;

import java.util.function.Consumer;
import java.util.function.Predicate;

/*
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  ⚠️  已停用（方案B → 方案A 升级）                                            ║
 * ║                                                                              ║
 * ║  此类是自研状态机（方案B，PaymentStateMachine）使用的"转换定义"数据对象。      ║
 * ║  升级到 COLA StateMachine（方案A）后，COLA 在内部通过 Builder DSL 管理转换，   ║
 * ║  无需此外部数据类。                                                            ║
 * ║                                                                              ║
 * ║  代码保留作为参考，不再被任何 Spring Bean 引用。                               ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 */

/**
 * 支付状态转换定义（方案B 自研状态机专用）— 已停用，COLA 内部自管理转换定义
 *
 * @deprecated 随 PaymentStateMachine（方案B）一起停用，升级至 COLA StateMachine（方案A）
 */
@Deprecated
@Data
@Builder
public class PaymentTransition {

    /** 源状态 */
    private PaymentStatus from;

    /** 目标状态 */
    private PaymentStatus to;

    /** 可读描述（日志/后台展示用） */
    private String description;

    /**
     * Guard（前置条件校验）
     * 返回 false 时抛 IllegalStateTransitionException，阻止状态转换
     */
    private Predicate<TransitionContext> guard;

    /**
     * Action（转换动作，仅当 guard 通过后执行）
     * 通常为日志记录、事件发布等副作用，不做 DB 写入（DB 写入由调用方负责）
     */
    private Consumer<TransitionContext> action;
}
