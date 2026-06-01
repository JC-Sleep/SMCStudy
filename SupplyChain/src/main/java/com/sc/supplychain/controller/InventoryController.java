package com.sc.supplychain.controller;

import com.sc.supplychain.dto.request.InboundRequest;
import com.sc.supplychain.dto.response.ApiResponse;
import com.sc.supplychain.service.InventoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "分布式库存")
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(summary = "入库（支持批次号、生产日期、过期日期）")
    @PostMapping("/inbound")
    public ApiResponse<Void> inbound(@Validated @RequestBody InboundRequest req) {
        inventoryService.inbound(req);
        return ApiResponse.ok();
    }

    @Operation(summary = "查询仓库级库存（DB 数据）")
    @GetMapping("/{warehouseId}/{skuId}")
    public ApiResponse<?> getInventory(@PathVariable Long warehouseId,
                                       @PathVariable Long skuId) {
        return ApiResponse.ok(inventoryService.getInventory(warehouseId, skuId));
    }

    @Operation(summary = "查询 Redis 实时可用量")
    @GetMapping("/{warehouseId}/{skuId}/redis")
    public ApiResponse<Long> getRedisStock(@PathVariable Long warehouseId,
                                           @PathVariable Long skuId) {
        return ApiResponse.ok(inventoryService.getAvailableFromRedis(warehouseId, skuId));
    }

    @Operation(summary = "手动触发 Redis 库存预热（从 DB 同步到 Redis）")
    @PostMapping("/{warehouseId}/{skuId}/warmup")
    public ApiResponse<Void> warmup(@PathVariable Long warehouseId,
                                    @PathVariable Long skuId) {
        inventoryService.warmupRedisStock(warehouseId, skuId);
        return ApiResponse.ok();
    }

    @Operation(summary = "手动触发全量对账（Redis vs DB）")
    @PostMapping("/reconcile")
    public ApiResponse<Void> reconcile() {
        inventoryService.reconcile();
        return ApiResponse.ok();
    }
}
