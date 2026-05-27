package com.sc.supplychain.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 新增/编辑 SPU 请求 */
@Data
public class SpuRequest {

    @NotBlank(message = "商品名称不能为空")
    private String spuName;

    private Long categoryId;

    private String brand;

    /** NORMAL / FRESH / FROZEN / CHILLED */
    private String freshType = "NORMAL";

    private String description;
}

