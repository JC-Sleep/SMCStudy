package com.sc.supplychain.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 取货 / 签收码核对 */
@Data
public class DeliveryCodeRequest {
    @NotNull private Long riderId;
    @NotBlank private String code;
}

