package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 骑手实体（Phase2.1 落地版） */
@Data
@TableName("sc_rider")
public class Rider implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String riderName;
    private String phone;

    /** {@link com.sc.supplychain.enums.RiderStatus} IDLE / BUSY / OFFLINE */
    private String status;

    /** 所属仓库（限制只接该仓订单） */
    private Long warehouseId;

    /** EBIKE / MOTOR / CAR / WALK */
    private String vehicleType;

    private BigDecimal currentLng;
    private BigDecimal currentLat;

    /** 同时配送上限 */
    private Integer maxParallel;

    /** 当前在途单数 */
    private Integer currentLoad;

    private BigDecimal rating;
    private Integer todayOrders;

    /** 0=离线 1=在线 */
    private Integer online;

    private LocalDateTime lastHeartbeat;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
