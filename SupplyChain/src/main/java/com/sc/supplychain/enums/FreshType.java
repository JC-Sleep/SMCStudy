package com.sc.supplychain.enums;

import lombok.Getter;

/** 生鲜品类型 */
@Getter
public enum FreshType {
    NORMAL("NORMAL", "普通商品"),
    FRESH("FRESH", "生鲜"),
    FROZEN("FROZEN", "冷冻"),
    CHILLED("CHILLED", "冷藏");

    private final String code;
    private final String desc;

    FreshType(String code, String desc) { this.code = code; this.desc = desc; }
}
