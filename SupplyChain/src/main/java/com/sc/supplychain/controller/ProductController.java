package com.sc.supplychain.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sc.supplychain.dto.request.SkuRequest;
import com.sc.supplychain.dto.request.SpuRequest;
import com.sc.supplychain.dto.response.ApiResponse;
import com.sc.supplychain.service.ProductService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "商品中心（SPU/SKU）")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "新建 SPU")
    @PostMapping("/spu")
    public ApiResponse<Long> createSpu(@Validated @RequestBody SpuRequest req) {
        return ApiResponse.ok(productService.createSpu(req));
    }

    @Operation(summary = "SPU 上架")
    @PutMapping("/spu/{spuId}/on-sale")
    public ApiResponse<Void> onSale(@PathVariable Long spuId) {
        productService.onSale(spuId);
        return ApiResponse.ok();
    }

    @Operation(summary = "SPU 下架")
    @PutMapping("/spu/{spuId}/off-sale")
    public ApiResponse<Void> offSale(@PathVariable Long spuId) {
        productService.offSale(spuId);
        return ApiResponse.ok();
    }

    @Operation(summary = "分页查询 SPU")
    @GetMapping("/spu/list")
    public ApiResponse<?> listSpus(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(productService.pageSpus(new Page<>(page, size), keyword, status));
    }

    @Operation(summary = "获取 SPU 详情")
    @GetMapping("/spu/{spuId}")
    public ApiResponse<?> getSpu(@PathVariable Long spuId) {
        return ApiResponse.ok(productService.getSpuById(spuId));
    }

    @Operation(summary = "新建 SKU")
    @PostMapping("/sku")
    public ApiResponse<Long> createSku(@Validated @RequestBody SkuRequest req) {
        return ApiResponse.ok(productService.createSku(req));
    }

    @Operation(summary = "查询 SPU 下所有 SKU")
    @GetMapping("/sku/by-spu/{spuId}")
    public ApiResponse<?> listSkus(@PathVariable Long spuId) {
        return ApiResponse.ok(productService.listSkuBySpuId(spuId));
    }

    @Operation(summary = "启用/禁用 SKU")
    @PutMapping("/sku/{skuId}/toggle")
    public ApiResponse<Void> toggleSku(@PathVariable Long skuId,
                                       @RequestParam boolean enable) {
        productService.toggleSkuStatus(skuId, enable);
        return ApiResponse.ok();
    }
}
