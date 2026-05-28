package com.sc.supplychain.dto.request;

import lombok.Data;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 入库请求 */
@Data
public class InboundRequest {

    @NotNull(message = "SKU ID 不能为空")
    private Long skuId;

    @NotNull(message = "仓库 ID 不能为空")
    private Long warehouseId;

    @Min(value = 1, message = "入库数量至少为1")
    private int qty;

    /** 批次号（不传则自动生成） */
    private String batchNo;

    private LocalDate productDate;

    private LocalDate expireDate;
}
