package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品分类（多级树，parentId=0 为顶级）
 */
@Data
@TableName("sc_category")
public class SpuCategory implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 父分类ID（0=顶级） */
    private Long parentId;

    private String catName;

    /** 层级 1/2/3 */
    private Integer level;

    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

