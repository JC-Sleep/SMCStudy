package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 抢单池 */
@Data
@TableName("sc_delivery_grab_pool")
public class DeliveryGrabPool implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deliveryId;
    private Long warehouseId;
    private BigDecimal broadcastLng;
    private BigDecimal broadcastLat;
    private Integer broadcastRadiusM;
    private LocalDateTime expireTime;
    private Long grabbedBy;
    private LocalDateTime grabTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

