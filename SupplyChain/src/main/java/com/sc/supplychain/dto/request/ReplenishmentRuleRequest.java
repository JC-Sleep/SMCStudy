package com.sc.supplychain.dto.request;

import lombok.Data;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/** 补货规则请求 */
@Data
public class ReplenishmentRuleRequest {

    @NotNull private Long skuId;
    @NotNull private Long warehouseId;

    @Min(value = 0, message = "最小库存不能为负")
    private int minQty;

    @Min(value = 1, message = "补货量至少为1")
    private int replenishQty;

    private Long supplierId;
}
