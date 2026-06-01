package com.sc.supplychain.controller;

import com.sc.supplychain.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

/**
 * 骑手配送接口（Phase2预留外壳）
 * 状态机: PENDING → ASSIGNED → PICKING → IN_TRANSIT → DELIVERED / FAILED
 * TODO Phase2: 实现自动派单、实时位置上报（WebSocket）、超时重派
 */
@Tag(name = "骑手配送【Phase2预留】")
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {

    @Operation(summary = "派单给骑手 [TODO Phase2]")
    @PostMapping("/assign")
    public ApiResponse<Void> assign(@RequestParam Long fulfillmentOrderId,
                                    @RequestParam Long riderId) {
        // TODO Phase2: 创建配送单，更新履约订单状态 OUTBOUND → DELIVERING
        return ApiResponse.error(501, "Phase2 待实现：骑手配送 assign");
    }

    @Operation(summary = "骑手取货确认 [TODO Phase2]")
    @PostMapping("/{deliveryId}/pickup")
    public ApiResponse<Void> pickup(@PathVariable Long deliveryId) {
        // TODO Phase2: status ASSIGNED → PICKING → IN_TRANSIT
        return ApiResponse.error(501, "Phase2 待实现：pickup");
    }

    @Operation(summary = "骑手送达确认 [TODO Phase2]")
    @PostMapping("/{deliveryId}/delivered")
    public ApiResponse<Void> delivered(@PathVariable Long deliveryId) {
        // TODO Phase2: status IN_TRANSIT → DELIVERED，同步履约订单 DELIVERED
        return ApiResponse.error(501, "Phase2 待实现：delivered");
    }

    @Operation(summary = "查询配送单 [TODO Phase2]")
    @GetMapping("/{deliveryId}")
    public ApiResponse<Void> getDelivery(@PathVariable Long deliveryId) {
        // TODO Phase2: 查询配送单 + 骑手实时位置
        return ApiResponse.error(501, "Phase2 待实现：query delivery");
    }
}
