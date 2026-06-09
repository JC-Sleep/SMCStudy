package com.sc.supplychain.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 上报配送异常 */
@Data
public class DeliveryExceptionRequest {
    @NotNull private Long riderId;
    /** PICKUP_TIMEOUT/CUSTOMER_NOT_AVAILABLE/GOODS_DAMAGED/CUSTOMER_REJECTED ... */
    @NotBlank private String exceptionType;
    private String detail;
}

