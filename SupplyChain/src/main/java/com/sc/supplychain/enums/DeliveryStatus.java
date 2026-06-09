package com.sc.supplychain.enums;

import lombok.Getter;

/** 骑手配送状态机（Phase2.1） */
@Getter
public enum DeliveryStatus {
    PENDING("PENDING", "待派单"),
    GRABBING("GRABBING", "抢单池广播中"),
    ASSIGNED("ASSIGNED", "已派单/接单"),
    REJECTED("REJECTED", "骑手拒单（中间态）"),
    PICKING("PICKING", "取货中"),
    IN_TRANSIT("IN_TRANSIT", "配送中"),
    DELIVERED("DELIVERED", "已送达"),
    RETURNED("RETURNED", "客户拒收已退回"),
    REASSIGNED("REASSIGNED", "已改派"),
    TIMEOUT("TIMEOUT", "超时（仍可继续）"),
    FAILED("FAILED", "配送失败");

    private final String code;
    private final String desc;

    DeliveryStatus(String code, String desc) { this.code = code; this.desc = desc; }
}
