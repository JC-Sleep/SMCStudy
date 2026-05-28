package com.sc.supplychain.enums;

import lombok.Getter;

/** 库存操作类型（流水记录） */
@Getter
public enum InventoryOpType {
    INBOUND("INBOUND", "入库"),
    LOCK("LOCK", "预扣锁定"),
    UNLOCK("UNLOCK", "释放预扣"),
    CONFIRM("CONFIRM", "出库确认"),
    WRITEOFF("WRITEOFF", "报损"),
    RECONCILE_FIX("RECONCILE_FIX", "对账修复");

    private final String code;
    private final String desc;

    InventoryOpType(String code, String desc) { this.code = code; this.desc = desc; }
}
