package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 效期预警记录 */
@Data
@TableName("sc_expiry_warning")
public class ExpiryWarning implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String batchNo;

    private Long skuId;

    /** NEAR_EXPIRY / URGENT */
    private String warnLevel;

    private LocalDate expireDate;

    private Integer remainQty;

    /** 0=未处理 1=已处理 */
    private Integer isHandled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime warnTime;
}

