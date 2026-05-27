package com.sc.supplychain.dto.request;

import lombok.Data;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 新增 SKU 请求 */
@Data
public class SkuRequest {

    @NotNull(message = "SPU ID 不能为空")
    private Long spuId;

    @NotBlank(message = "SKU名称不能为空")
    private String skuName;

    /**
     * 多级属性 JSON 字符串
     * 示例: [{"attrKey":"规格","attrVal":"500g"},{"attrKey":"产地","attrVal":"广东"}]
     */
    private String skuAttrs;

    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    private BigDecimal weight;

    private String imgUrl;
}

