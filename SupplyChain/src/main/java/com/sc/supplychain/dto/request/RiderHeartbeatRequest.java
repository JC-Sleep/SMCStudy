package com.sc.supplychain.dto.request;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 骑手心跳/位置上报 */
@Data
public class RiderHeartbeatRequest {
    @NotNull private Long riderId;
    @NotNull private BigDecimal lng;
    @NotNull private BigDecimal lat;
    private BigDecimal speedKmh;
}

