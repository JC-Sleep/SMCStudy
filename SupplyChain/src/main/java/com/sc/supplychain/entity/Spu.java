package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标准商品单元（SPU）
 */
@Data
@TableName("sc_spu")
public class Spu implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String spuName;

    /** 分类ID（关联 sc_category） */
    private Long categoryId;

    private String brand;

    /**
     * 生鲜类型 {@link com.sc.supplychain.enums.FreshType}
     * NORMAL / FRESH / FROZEN / CHILLED
     */
    private String freshType;

    /**
     * 商品状态 {@link com.sc.supplychain.enums.ProductStatus}
     * DRAFT / ON_SALE / OFF_SALE
     */
    private String status;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
