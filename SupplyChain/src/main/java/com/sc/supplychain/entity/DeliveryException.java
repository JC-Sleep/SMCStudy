package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 配送异常工单 */
@Data
@TableName("sc_delivery_exception")
public class DeliveryException implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long deliveryId;
    /** PICKUP_TIMEOUT / DELIVER_TIMEOUT / CUSTOMER_NOT_AVAILABLE / GOODS_DAMAGED / RIDER_REJECTED / CUSTOMER_REJECTED */
    private String exceptionType;
    private String exceptionDetail;
    private Long handlerId;
    /** OPEN / CLOSED */
    private String handleStatus;
    private String handleRemark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private LocalDateTime handleTime;
}

