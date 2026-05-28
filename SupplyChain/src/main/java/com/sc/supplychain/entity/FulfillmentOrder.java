package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * O2O 履约订单
 * 状态机: PENDING → PAID → PICKING → OUTBOUND → DELIVERING → DELIVERED
 *                                              ↘ CANCELLED
 */
@Data
@TableName("sc_fulfillment_order")
public class FulfillmentOrder implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Long storeId;

    private Long skuId;

    private Integer qty;

    /**
     * {@link com.sc.supplychain.enums.FulfillmentOrderStatus}
     */
    private String status;

    private BigDecimal payAmount;

    private LocalDateTime payTime;

    private String address;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
