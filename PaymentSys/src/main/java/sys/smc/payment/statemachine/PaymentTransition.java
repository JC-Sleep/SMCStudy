package sys.smc.payment.statemachine;

import lombok.Builder;
import lombok.Data;
import sys.smc.payment.enums.PaymentStatus;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 支付状态转换定义
 *
 * 一条转换 = 源状态 + 目标状态 + （可选）guard 条件 + （可选）action 动作
 *
 * guard 为 null → 无条件允许（只要 from→to 在白名单中即可）
 * action 为 null → 无副作用（只做日志）
 */
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

