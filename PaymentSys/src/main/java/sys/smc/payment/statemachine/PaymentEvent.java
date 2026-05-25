package sys.smc.payment.statemachine;

/**
 * 支付状态机事件枚举（COLA StateMachine 方案A 专用）
 *
 * 每个枚举值代表一个"触发状态转换的业务事件"。
 * COLA 的设计理念：状态机是无状态的定义器，你每次告诉它"我现在在哪个状态 + 发生了什么事件"，
 * 它返回"你应该去哪个状态"，实际状态存储仍在 DB 中。
 *
 * 事件 ↔ 状态转换映射：
 *   SUBMIT           : INIT          → PENDING
 *   BANK_CONFIRM     : PENDING       → SUCCESS          (guard: signatureValid=true)
 *   BANK_DECLINE     : PENDING       → FAILED
 *   SYSTEM_TIMEOUT   : PENDING       → TIMEOUT
 *   RECONCILE_START  : TIMEOUT       → RECONCILING
 *   RECON_SUCCESS    : RECONCILING   → SUCCESS
 *   RECON_FAIL       : RECONCILING   → FAILED
 *   REFUND_APPLY     : SUCCESS       → REFUNDING
 *                      PARTIALLY_REFUNDED → REFUNDING
 *                      REFUND_FAILED → REFUNDING
 *   REFUND_COMPLETE  : REFUNDING     → REFUNDED
 *   PARTIAL_REFUND   : REFUNDING     → PARTIALLY_REFUNDED
 *   REFUND_FAIL      : REFUNDING     → REFUND_FAILED
 *
 * @see sys.smc.payment.statemachine.PaymentStateMachineConfig   状态机 Bean 定义（COLA 注册）
 * @see sys.smc.payment.statemachine.PaymentStateMachineService  对外服务封装（提供与自研状态机相同的 transition 接口）
 */
public enum PaymentEvent {

    /** 提交支付到银行（INIT → PENDING） */
    SUBMIT,

    /** 银行回调确认扣款成功（PENDING → SUCCESS）—— guard: signatureValid=true */
    BANK_CONFIRM,

    /** 银行回调拒绝扣款（PENDING → FAILED） */
    BANK_DECLINE,

    /** 系统超时未收到银行回调（PENDING → TIMEOUT） */
    SYSTEM_TIMEOUT,

    /** 对账 Job 介入，进入核查中间态（TIMEOUT → RECONCILING） */
    RECONCILE_START,

    /** 对账确认银行已扣款（RECONCILING → SUCCESS） */
    RECON_SUCCESS,

    /** 对账确认银行未扣款（RECONCILING → FAILED） */
    RECON_FAIL,

    /** 退款申请通过，进入退款处理中（SUCCESS/PARTIALLY_REFUNDED/REFUND_FAILED → REFUNDING） */
    REFUND_APPLY,

    /** 全额退款完成（REFUNDING → REFUNDED） */
    REFUND_COMPLETE,

    /** 部分退款完成（REFUNDING → PARTIALLY_REFUNDED） */
    PARTIAL_REFUND,

    /** 退款执行失败，需人工干预（REFUNDING → REFUND_FAILED） */
    REFUND_FAIL
}


