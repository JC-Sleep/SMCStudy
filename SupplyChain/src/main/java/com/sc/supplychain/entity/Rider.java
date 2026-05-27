package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 骑手（Phase2预留） */
@Data
@TableName("sc_rider")
public class Rider implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String riderName;

    private String phone;

    /** IDLE / BUSY / OFFLINE */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

