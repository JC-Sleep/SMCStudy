package com.sc.supplychain.controller;

import com.sc.supplychain.dto.request.OrderCreateRequest;
import com.sc.supplychain.dto.response.ApiResponse;
import com.sc.supplychain.mapper.ExpiryWarningMapper;
import com.sc.supplychain.service.FulfillmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "O2O 履约 + 效期预警")
@RestController
@RequestMapping("/api/fulfillment")
@RequiredArgsConstructor
public class FulfillmentController {

    private final FulfillmentService fulfillmentService;
    private final ExpiryWarningMapper expiryWarningMapper;

    @Operation(summary = "O2O 下单（Redis预扣 + 写DB + MQ落库）")
    @PostMapping("/order/create")
    public ApiResponse<String> createOrder(@Validated @RequestBody OrderCreateRequest req) {
        return ApiResponse.ok(fulfillmentService.createOrder(req));
    }

    @Operation(summary = "取消订单（归还库存）")
    @PostMapping("/order/{orderNo}/cancel")
    public ApiResponse<Void> cancelOrder(@PathVariable String orderNo) {
        fulfillmentService.cancelOrder(orderNo);
        return ApiResponse.ok();
    }

    @Operation(summary = "支付回调（状态: PENDING → PAID）")
    @PostMapping("/order/{orderNo}/paid")
    public ApiResponse<Void> onPaid(@PathVariable String orderNo) {
        fulfillmentService.onPaid(orderNo);
        return ApiResponse.ok();
    }

    @Operation(summary = "出库确认（FIFO批次分配，状态: PAID → OUTBOUND）")
    @PostMapping("/order/{orderNo}/outbound")
    public ApiResponse<?> outbound(@PathVariable String orderNo) {
        fulfillmentService.outbound(orderNo);
        return ApiResponse.ok();
    }

    @Operation(summary = "查询订单详情")
    @GetMapping("/order/{orderNo}")
    public ApiResponse<?> getOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(fulfillmentService.getByOrderNo(orderNo));
    }

    @Operation(summary = "查询小店订单列表")
    @GetMapping("/order/list")
    public ApiResponse<?> listByStore(@RequestParam Long storeId) {
        return ApiResponse.ok(fulfillmentService.listByStore(storeId));
    }

    // ── 效期预警 ────────────────────────────────────────────────────

    @Operation(summary = "查询未处理效期预警列表")
    @GetMapping("/expiry/warning/list")
    public ApiResponse<?> listWarnings() {
        return ApiResponse.ok(expiryWarningMapper.selectUnhandled());
    }
}
