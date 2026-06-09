package com.sc.supplychain.enums;

import lombok.Getter;

/** 骑手在线状态 */
@Getter
public enum RiderStatus {
    IDLE("IDLE", "空闲"),
    BUSY("BUSY", "配送中"),
    OFFLINE("OFFLINE", "离线");

    private final String code;
    private final String desc;
    RiderStatus(String code, String desc) { this.code = code; this.desc = desc; }
}

