package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 配送轨迹 */
@Data
@TableName("sc_delivery_track")
public class DeliveryTrack implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deliveryId;
    private Long riderId;
    private BigDecimal lng;
    private BigDecimal lat;
    private BigDecimal speedKmh;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime reportTime;
}

