package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存批次（FIFO 出库依据 + 效期管理）
 * Redis ZSet: inventory:batch:{warehouseId}:{skuId}
 *   score = inboundTime（Unix 毫秒）, member = batchNo
 */
@Data
@TableName("sc_inventory_batch")
public class InventoryBatch implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 批次号（唯一，如 BATCH-20260527-0001） */
    private String batchNo;

    private Long skuId;

    private Long warehouseId;

    private Integer inboundQty;

    private Integer remainQty;

    private LocalDate productDate;

    private LocalDate expireDate;

    /** FIFO 排序字段（入库时间） */
    private LocalDateTime inboundTime;

    /**
     * 批次状态 {@link com.sc.supplychain.enums.BatchStatus}
     * NORMAL / NEAR_EXPIRY / URGENT / EXPIRED / WRITTEN_OFF
     */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
