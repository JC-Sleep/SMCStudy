package com.sc.supplychain.enums;

import lombok.Getter;

/** 骑手配送状态（Phase2预留） */
@Getter
public enum DeliveryStatus {
    PENDING("PENDING", "待派单"),
    ASSIGNED("ASSIGNED", "已派单"),
    PICKING("PICKING", "取货中"),
    IN_TRANSIT("IN_TRANSIT", "配送中"),
    DELIVERED("DELIVERED", "已完成"),
    FAILED("FAILED", "配送失败");

    private final String code;
    private final String desc;

    DeliveryStatus(String code, String desc) { this.code = code; this.desc = desc; }
}
