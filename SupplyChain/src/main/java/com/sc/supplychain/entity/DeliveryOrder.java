package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 配送单（Phase2.1 落地版） */
@Data
@TableName("sc_delivery_order")
public class DeliveryOrder implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fulfillmentOrderId;
    private Long riderId;
    private Long warehouseId;

    /** {@link com.sc.supplychain.enums.DeliveryStatus} */
    private String status;

    private LocalDateTime assignTime;
    private LocalDateTime pickupTime;
    private LocalDateTime deliveredTime;
    private String failReason;

    // 地址 + 经纬度
    private String pickupAddress;
    private BigDecimal pickupLng;
    private BigDecimal pickupLat;
    private String deliverAddress;
    private BigDecimal deliverLng;
    private BigDecimal deliverLat;

    // 距离与时效
    private Integer distanceMeters;
    private Integer estimatedMinutes;
    private LocalDateTime expectedPickupTime;
    private LocalDateTime expectedDeliverTime;

    // 验证码
    private String pickupCode;
    private String deliverCode;

    // 计费
    private BigDecimal baseFee;
    private BigDecimal distanceFee;
    private BigDecimal penaltyFee;
    private BigDecimal finalFee;

    private Integer reassignCount;
    private String customerRemark;
    private String customerPhoneMasked;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
