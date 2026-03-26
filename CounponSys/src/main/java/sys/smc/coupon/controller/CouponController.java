package sys.smc.coupon.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.coupon.dto.request.ClaimCouponRequest;
import sys.smc.coupon.dto.request.RedeemCouponRequest;
import sys.smc.coupon.dto.response.ApiResponse;
import sys.smc.coupon.dto.response.CouponDTO;
import sys.smc.coupon.dto.response.RedeemResult;
import sys.smc.coupon.service.CouponService;

import java.util.List;

/**
 * 优惠券Controller
 */
@Api(tags = "优惠券管理")
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @ApiOperation("领取优惠券")
    @PostMapping("/claim")
    public ApiResponse<CouponDTO> claimCoupon(@Validated @RequestBody ClaimCouponRequest request) {
        CouponDTO coupon = couponService.claimCoupon(request);
        return ApiResponse.success("领取成功", coupon);
    }

    @ApiOperation("核销优惠券")
    @PostMapping("/redeem")
    public ApiResponse<RedeemResult> redeemCoupon(@Validated @RequestBody RedeemCouponRequest request) {
        RedeemResult result = couponService.redeemCoupon(request);
        return ApiResponse.success(result);
    }

    @ApiOperation("查询用户优惠券列表")
    @GetMapping("/list")
    public ApiResponse<List<CouponDTO>> listUserCoupons(
            @ApiParam("用户ID") @RequestParam String userId,
            @ApiParam("状态:1可用,2已使用,3已过期") @RequestParam(required = false) Integer status) {
        List<CouponDTO> coupons = couponService.listUserCoupons(userId, status);
        return ApiResponse.success(coupons);
    }

    @ApiOperation("查询优惠券详情")
    @GetMapping("/detail")
    public ApiResponse<CouponDTO> getCouponDetail(
            @ApiParam("券码") @RequestParam String couponCode) {
        CouponDTO coupon = couponService.getCouponByCode(couponCode);
        return ApiResponse.success(coupon);
    }
}

