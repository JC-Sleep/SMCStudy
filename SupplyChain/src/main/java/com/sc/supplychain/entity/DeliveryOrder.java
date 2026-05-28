package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 配送单（Phase2预留） */
@Data
@TableName("sc_delivery_order")
public class DeliveryOrder implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fulfillmentOrderId;

    private Long riderId;

    /**
     * 配送状态 {@link com.sc.supplychain.enums.DeliveryStatus}
     * PENDING/ASSIGNED/PICKING/IN_TRANSIT/DELIVERED/FAILED
     */
    private String status;

    private LocalDateTime assignTime;

    private LocalDateTime pickupTime;

    private LocalDateTime deliveredTime;

    private String failReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
