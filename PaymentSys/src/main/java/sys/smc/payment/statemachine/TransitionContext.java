package sys.smc.payment.statemachine;

import lombok.Builder;
import lombok.Data;
import sys.smc.payment.entity.PaymentTransaction;

/**
 * 状态转换上下文
 *
 * 作为 guard 条件校验和 action 执行的携带信息载体。
 * 每次调用 PaymentStateMachine.transition() 时构建一个新实例，
 * 无需持久化（状态机本身是无状态的）。
 */
@Data
@Builder
public class TransitionContext {

    /**
     * 当前支付交易对象（从 DB 读取，含 version 字段用于乐观锁）
     */
    private PaymentTransaction transaction;

    /**
     * 触发操作人标识，如 "CALLBACK_CYBERSOURCE"、"RECONCILIATION_JOB"、"FINANCE_USER_001"
     */
    private String operator;

    /**
     * 操作备注（可选，对账修正/退款原因等）
     */
    private String remark;

    /**
     * 签名验证结果（专用于 BANK_CONFIRM 的 guard 条件）
     * 银行回调场景：只有签名有效才允许 PENDING → SUCCESS
     * 非回调场景：固定传 true
     */
    private boolean signatureValid;
}

