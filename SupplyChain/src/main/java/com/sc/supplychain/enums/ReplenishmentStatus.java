package com.sc.supplychain.enums;

import lombok.Getter;

/** 补货单状态 */
@Getter
public enum ReplenishmentStatus {
    PENDING("PENDING", "待处理"),
    CONFIRMED("CONFIRMED", "已确认"),
    INBOUND("INBOUND", "已入库"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String desc;

    ReplenishmentStatus(String code, String desc) { this.code = code; this.desc = desc; }
}


