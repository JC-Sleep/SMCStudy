package com.sc.supplychain.enums;

import lombok.Getter;

/** 库存批次状态（效期四级） */
@Getter
public enum BatchStatus {
    NORMAL("NORMAL", "正常"),
    NEAR_EXPIRY("NEAR_EXPIRY", "临期预警（黄）"),
    URGENT("URGENT", "紧急预警（红）"),
    EXPIRED("EXPIRED", "已过期"),
    WRITTEN_OFF("WRITTEN_OFF", "已报损");

    private final String code;
    private final String desc;

    BatchStatus(String code, String desc) { this.code = code; this.desc = desc; }
}

