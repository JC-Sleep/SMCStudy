package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 补货单（由 ReplenishmentCheckJob 自动生成，或手动创建） */
@Data
@TableName("sc_replenishment_order")
public class ReplenishmentOrder implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long ruleId;

    private Long skuId;

    private Long warehouseId;

    private Integer qty;

    /**
     * 补货单状态 {@link com.sc.supplychain.enums.ReplenishmentStatus}
     */
    private String status;

    private LocalDateTime triggerTime;

    private LocalDateTime confirmTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
