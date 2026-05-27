package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 社区小店 */
@Data
@TableName("sc_store")
public class Store implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String storeName;

    private Long communityId;

    private String address;

    private String phone;

    /** OPEN / CLOSED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}

