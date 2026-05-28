package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 仓库 */
@Data
@TableName("sc_warehouse")
public class Warehouse implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String warehouseName;

    private String address;

    /** MAIN=大仓 / STORE=小店仓 */
    private String type;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
