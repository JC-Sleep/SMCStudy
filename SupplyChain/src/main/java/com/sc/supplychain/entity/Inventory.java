package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 仓库级库存（与 Redis 镜像保持最终一致）
 * Redis key: inventory:available:{warehouseId}:{skuId}
 */
@Data
@TableName("sc_inventory")
public class Inventory implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long skuId;

    private Long warehouseId;

    /** 可用库存（Redis 预扣的 DB 镜像） */
    private Integer availableQty;

    /** 锁定库存（已预扣, 待出库确认） */
    private Integer lockedQty;

    /** 总库存 = availableQty + lockedQty */
    private Integer totalQty;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
