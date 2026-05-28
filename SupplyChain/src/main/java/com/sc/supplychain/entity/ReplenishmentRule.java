package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 自动补货规则 */
@Data
@TableName("sc_replenishment_rule")
public class ReplenishmentRule implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long skuId;

    private Long warehouseId;

    /** 触发阈值：可用库存低于此值时触发 */
    private Integer minQty;

    /** 每次补货量 */
    private Integer replenishQty;

    private Long supplierId;

    /** 1=启用 0=禁用 */
    private Integer isEnabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
