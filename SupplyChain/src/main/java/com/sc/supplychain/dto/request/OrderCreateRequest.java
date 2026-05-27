package com.sc.supplychain.dto.request;

import lombok.Data;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** O2O 下单请求 */
@Data
public class OrderCreateRequest {

    @NotNull(message = "小店ID不能为空")
    private Long storeId;

    @NotNull(message = "SKU ID不能为空")
    private Long skuId;

    @Min(value = 1, message = "购买数量至少为1")
    private int qty;

    @DecimalMin(value = "0.01", message = "支付金额必须大于0")
    private BigDecimal payAmount;

    @NotBlank(message = "收货地址不能为空")
    private String address;

    /** 默认仓库ID（可选，不传则用主仓） */
    private Long warehouseId;
}

