package com.sc.supplychain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存单位（SKU）
 * skuAttrs 存 JSON 字符串：[{"attrKey":"规格","attrVal":"500g"},...]
 */
@Data
@TableName("sc_sku")
public class Sku implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 SPU */
    private Long spuId;

    private String skuName;

    /**
     * SKU 多级属性（MySQL JSON 列）
     * 示例: [{"attrKey":"规格","attrVal":"500g"},{"attrKey":"产地","attrVal":"广东"}]
     */
    private String skuAttrs;

    /** 售价 */
    private BigDecimal price;

    /** 克重 */
    private BigDecimal weight;

    private String imgUrl;

    /** ENABLED / DISABLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

