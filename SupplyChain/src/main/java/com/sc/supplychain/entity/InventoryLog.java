package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 库存操作流水（全量审计） */
@Data
@TableName("sc_inventory_log")
public class InventoryLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long skuId;

    private Long warehouseId;

    private String batchNo;

    /**
     * 操作类型 {@link com.sc.supplychain.enums.InventoryOpType}
     */
    private String opType;

    private Integer qtyBefore;

    private Integer qtyAfter;

    private Integer deltaQty;

    /** 关联单号（订单号/补货单号） */
    private String refNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

