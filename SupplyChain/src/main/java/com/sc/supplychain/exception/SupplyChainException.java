package com.sc.supplychain.exception;

import lombok.Getter;

/** 供应链业务异常 */
@Getter
public class SupplyChainException extends RuntimeException {

    private final int code;

    public SupplyChainException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static SupplyChainException of(String message) {
        return new SupplyChainException(400, message);
    }

    public static SupplyChainException stockInsufficient(Long skuId) {
        return new SupplyChainException(4001, "SKU[" + skuId + "] 库存不足");
    }

    public static SupplyChainException notFound(String resource, Object id) {
        return new SupplyChainException(4004, resource + "[" + id + "] 不存在");
    }

    public static SupplyChainException illegalStatus(String msg) {
        return new SupplyChainException(4002, "状态不合法: " + msg);
    }
}

