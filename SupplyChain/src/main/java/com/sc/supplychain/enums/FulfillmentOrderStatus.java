package com.sc.supplychain.enums;

import lombok.Getter;

/** O2O 履约订单状态机 */
@Getter
public enum FulfillmentOrderStatus {
    PENDING("PENDING", "待支付"),
    PAID("PAID", "已支付"),
    PICKING("PICKING", "拣货中"),
    OUTBOUND("OUTBOUND", "已出库"),
    DELIVERING("DELIVERING", "配送中（Phase2骑手）"),
    DELIVERED("DELIVERED", "已完成"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String desc;

    FulfillmentOrderStatus(String code, String desc) { this.code = code; this.desc = desc; }
}
