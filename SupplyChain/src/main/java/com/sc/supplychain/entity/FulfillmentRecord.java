package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 履约操作流水（审计日志） */
@Data
@TableName("sc_fulfillment_record")
public class FulfillmentRecord implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    /** CREATE / PAY / PICK / OUTBOUND / DELIVER / CANCEL */
    private String opType;

    private String opBy;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

