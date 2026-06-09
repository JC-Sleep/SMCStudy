package com.sc.supplychain.controller;

import com.sc.supplychain.dto.request.DeliveryCodeRequest;
import com.sc.supplychain.dto.request.DeliveryExceptionRequest;
import com.sc.supplychain.dto.request.RiderHeartbeatRequest;
import com.sc.supplychain.dto.response.ApiResponse;
import com.sc.supplychain.service.RiderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "骑手 App（C端）")
@RestController
@RequestMapping("/api/rider")
@RequiredArgsConstructor
public class RiderController {

    private final RiderService riderService;

    @Operation(summary = "上线")
    @PostMapping("/{riderId}/online")
    public ApiResponse<Void> online(@PathVariable Long riderId) {
        riderService.online(riderId);
        return ApiResponse.ok();
    }

    @Operation(summary = "下线（必须无在途单）")
    @PostMapping("/{riderId}/offline")
    public ApiResponse<Void> offline(@PathVariable Long riderId) {
        riderService.offline(riderId);
        return ApiResponse.ok();
    }

    @Operation(summary = "心跳+位置（30秒一次）")
    @PostMapping("/heartbeat")
    public ApiResponse<Void> heartbeat(@Validated @RequestBody RiderHeartbeatRequest req) {
        riderService.heartbeat(req.getRiderId(), req.getLng(), req.getLat());
        return ApiResponse.ok();
    }

    @Operation(summary = "确认接单（智能派单收到推送后调用）")
    @PostMapping("/{riderId}/accept/{deliveryId}")
    public ApiResponse<Void> accept(@PathVariable Long riderId, @PathVariable Long deliveryId) {
        riderService.acceptAssign(riderId, deliveryId);
        return ApiResponse.ok();
    }

    @Operation(summary = "拒单（一单只能拒一次）")
    @PostMapping("/{riderId}/reject/{deliveryId}")
    public ApiResponse<Void> reject(@PathVariable Long riderId, @PathVariable Long deliveryId) {
        riderService.reject(riderId, deliveryId);
        return ApiResponse.ok();
    }

    @Operation(summary = "抢单（先到先得，Lua SETNX 防超抢）")
    @PostMapping("/{riderId}/grab/{deliveryId}")
    public ApiResponse<Boolean> grab(@PathVariable Long riderId, @PathVariable Long deliveryId) {
        return ApiResponse.ok(riderService.grab(riderId, deliveryId));
    }

    @Operation(summary = "扫描取货码 → 进入配送中")
    @PostMapping("/{deliveryId}/pickup")
    public ApiResponse<Void> pickup(@PathVariable Long deliveryId,
                                    @Validated @RequestBody DeliveryCodeRequest req) {
        riderService.pickup(req.getRiderId(), deliveryId, req.getCode());
        return ApiResponse.ok();
    }

    @Operation(summary = "扫描签收码 → 完成")
    @PostMapping("/{deliveryId}/delivered")
    public ApiResponse<Void> delivered(@PathVariable Long deliveryId,
                                       @Validated @RequestBody DeliveryCodeRequest req) {
        riderService.delivered(req.getRiderId(), deliveryId, req.getCode());
        return ApiResponse.ok();
    }

    @Operation(summary = "上报异常")
    @PostMapping("/{deliveryId}/exception")
    public ApiResponse<Void> exception(@PathVariable Long deliveryId,
                                       @Validated @RequestBody DeliveryExceptionRequest req) {
        riderService.reportException(req.getRiderId(), deliveryId, req.getExceptionType(), req.getDetail());
        return ApiResponse.ok();
    }

    @Operation(summary = "我的资料")
    @GetMapping("/{riderId}")
    public ApiResponse<?> me(@PathVariable Long riderId) {
        return ApiResponse.ok(riderService.get(riderId));
    }

    @Operation(summary = "我的订单")
    @GetMapping("/{riderId}/orders")
    public ApiResponse<?> orders(@PathVariable Long riderId) {
        return ApiResponse.ok(riderService.myOrders(riderId));
    }
}

