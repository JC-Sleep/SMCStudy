package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 骑手收入流水 */
@Data
@TableName("sc_rider_income")
public class RiderIncome implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long riderId;
    private Long deliveryId;
    private BigDecimal baseFee;
    private BigDecimal distanceFee;
    private BigDecimal rushHourBonus;
    private BigDecimal weatherBonus;
    private BigDecimal penaltyFee;
    private BigDecimal total;
    /** PENDING / SETTLED */
    private String settleStatus;
    private LocalDateTime settleTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

