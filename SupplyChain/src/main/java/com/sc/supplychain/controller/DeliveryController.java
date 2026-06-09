package com.sc.supplychain.controller;

import com.sc.supplychain.dto.response.ApiResponse;
import com.sc.supplychain.mapper.DeliveryTrackMapper;
import com.sc.supplychain.service.DeliveryService;
import com.sc.supplychain.service.DispatchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sc.supplychain.entity.DeliveryTrack;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** B 端管理后台 — 骑手配送管理 */
@Tag(name = "骑手配送管理（B端）")
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final DispatchService dispatchService;
    private final DeliveryTrackMapper trackMapper;

    @Operation(summary = "查询配送单")
    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable Long id) {
        return ApiResponse.ok(deliveryService.get(id));
    }

    @Operation(summary = "手动派单（指定骑手）— 立即派给候选骑手中评分最高")
    @PostMapping("/{id}/dispatch")
    public ApiResponse<Boolean> dispatch(@PathVariable Long id) {
        return ApiResponse.ok(dispatchService.dispatch(id));
    }

    @Operation(summary = "强制改派")
    @PostMapping("/{id}/reassign")
    public ApiResponse<Void> reassign(@PathVariable Long id) {
        deliveryService.reassign(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "取消配送")
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestParam(required = false) String reason) {
        deliveryService.cancel(id, reason);
        return ApiResponse.ok();
    }

    @Operation(summary = "查骑手轨迹")
    @GetMapping("/{id}/track")
    public ApiResponse<?> track(@PathVariable Long id) {
        return ApiResponse.ok(trackMapper.selectList(new LambdaQueryWrapper<DeliveryTrack>()
                .eq(DeliveryTrack::getDeliveryId, id)
                .orderByAsc(DeliveryTrack::getReportTime)));
    }
}
