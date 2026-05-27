package com.sc.supplychain.enums;

import lombok.Getter;

/** 商品/SPU 状态 */
@Getter
public enum ProductStatus {
    DRAFT("DRAFT", "草稿"),
    ON_SALE("ON_SALE", "上架"),
    OFF_SALE("OFF_SALE", "下架");

    private final String code;
    private final String desc;

    ProductStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ProductStatus of(String code) {
        for (ProductStatus s : values()) {
            if (s.code.equals(code)) return s;
        }
        throw new IllegalArgumentException("Unknown ProductStatus: " + code);
    }
}

