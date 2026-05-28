package com.sc.supplychain.controller;

import com.sc.supplychain.dto.request.ReplenishmentRuleRequest;
import com.sc.supplychain.dto.response.ApiResponse;
import com.sc.supplychain.service.ReplenishmentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Api(tags = "自动补货")
@RestController
@RequestMapping("/api/replenishment")
@RequiredArgsConstructor
public class ReplenishmentController {

    private final ReplenishmentService replenishmentService;

    @ApiOperation("新增补货规则")
    @PostMapping("/rule")
    public ApiResponse<Long> createRule(@Validated @RequestBody ReplenishmentRuleRequest req) {
        return ApiResponse.ok(replenishmentService.createRule(req));
    }

    @ApiOperation("查询补货单列表")
    @GetMapping("/orders")
    public ApiResponse<?> listOrders(@RequestParam(required = false) String status) {
        return ApiResponse.ok(replenishmentService.listOrders(status));
    }

    @ApiOperation("确认补货单，触发入库")
    @PutMapping("/order/{orderId}/confirm")
    public ApiResponse<Void> confirmOrder(@PathVariable Long orderId) {
        replenishmentService.confirmOrder(orderId);
        return ApiResponse.ok();
    }

    @ApiOperation("手动触发补货扫描（测试用）")
    @PostMapping("/trigger")
    public ApiResponse<Void> manualTrigger() {
        replenishmentService.checkAndTrigger();
        return ApiResponse.ok();
    }
}
