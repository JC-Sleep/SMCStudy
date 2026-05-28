package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 金蝶云同步记录（Phase2预留） */
@Data
@TableName("sc_kingdee_sync")
public class KingdeeSync implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** SALES_VOUCHER / COST_VOUCHER / AP_VOUCHER */
    private String syncType;

    private String refNo;

    /** PENDING / SUCCESS / FAILED */
    private String syncStatus;

    private String requestBody;

    private String responseBody;

    private String failReason;

    private Integer retryCount;

    private LocalDateTime syncTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
