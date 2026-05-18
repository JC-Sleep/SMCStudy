package sys.smc.payment.enums;

/**
 * 退款申请状态枚举
 *
 * 状态流转：
 *   PENDING_REVIEW → APPROVED  → EXECUTING → COMPLETED（终态）
 *                 ↘ REJECTED（终态）         ↘ FAILED（需人工干预）
 */
public enum RefundApplicationStatus {

    /** 待财务审批 */
    PENDING_REVIEW("待审批"),

    /** 财务已批准，等待执行（触发异步退款） */
    APPROVED("已批准"),

    /** 拒绝退款（终态） */
    REJECTED("已拒绝"),

    /** 正在执行退款（防止并发重复执行） */
    EXECUTING("执行中"),

    /** 退款成功（终态） */
    COMPLETED("已完成"),

    /** 退款执行失败（需人工干预） */
    FAILED("执行失败");

    private final String description;

    RefundApplicationStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

