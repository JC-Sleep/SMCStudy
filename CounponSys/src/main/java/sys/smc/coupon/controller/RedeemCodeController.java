package sys.smc.coupon.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import sys.smc.coupon.dto.response.ApiResponse;
import sys.smc.coupon.entity.CouponRedeemCode;
import sys.smc.coupon.entity.RedeemCodeBatch;
import sys.smc.coupon.service.impl.RedeemCodeServiceImpl;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Date;
import java.util.List;

/**
 * 兑换码 REST API
 *
 * POST /api/redeem-code/batch/generate  → 后台批量生成兑换码
 * POST /api/redeem-code/redeem          → 用户兑换
 * GET  /api/redeem-code/batch/{batchId} → 查看批次信息
 * GET  /api/redeem-code/batch/{batchId}/codes → 查看批次下所有码（管理后台）
 */
@Api(tags = "兑换码管理")
@RestController
@RequestMapping("/api/redeem-code")
@RequiredArgsConstructor
public class RedeemCodeController {

    private final RedeemCodeServiceImpl redeemCodeService;

    // ======================== 管理端接口 ========================

    @ApiOperation("批量生成兑换码（管理后台）")
    @PostMapping("/batch/generate")
    public ApiResponse<Long> generateBatch(@Valid @RequestBody GenerateRequest req) {
        Long batchId = redeemCodeService.generateBatch(
                req.getTemplateId(),
                req.getCount(),
                req.getExpireTime(),
                req.getBatchName(),
                req.getOperatorId()
        );
        return ApiResponse.success("兑换码生成成功", batchId);
    }

    @ApiOperation("查询批次信息")
    @GetMapping("/batch/{batchId}")
    public ApiResponse<RedeemCodeBatch> getBatch(
            @ApiParam("批次ID") @PathVariable Long batchId) {
        return ApiResponse.success(redeemCodeService.getBatch(batchId));
    }

    @ApiOperation("查询批次下的兑换码列表（管理后台，生产环境限制访问）")
    @GetMapping("/batch/{batchId}/codes")
    public ApiResponse<List<CouponRedeemCode>> listCodes(
            @ApiParam("批次ID") @PathVariable Long batchId) {
        return ApiResponse.success(redeemCodeService.listByBatch(batchId));
    }

    // ======================== 用户端接口 ========================

    @ApiOperation("兑换码兑换（用户输入兑换码领券）")
    @PostMapping("/redeem")
    public ApiResponse<Long> redeem(
            @Valid @RequestBody RedeemRequest req,
            HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);
        Long couponId = redeemCodeService.redeem(
                req.getCode(),
                req.getUserId(),
                clientIp,
                req.getChannel() != null ? req.getChannel() : "APP"
        );
        return ApiResponse.success("兑换成功！优惠券已发放到您的账户", couponId);
    }

    // ======================== 请求DTO ========================

    @Data
    public static class GenerateRequest {
        @NotNull(message = "券模板ID不能为空")
        private Long templateId;

        @NotNull @Min(1) @Max(100000)
        private Integer count;

        @NotNull(message = "过期时间不能为空")
        private Date expireTime;

        @NotBlank(message = "批次名称不能为空")
        private String batchName;

        private String operatorId;
    }

    @Data
    public static class RedeemRequest {
        @NotBlank(message = "兑换码不能为空")
        private String code;

        @NotBlank(message = "用户ID不能为空")
        private String userId;

        /** 兑换渠道: APP / WEB / H5 */
        private String channel;
    }

    // ======================== 工具方法 ========================

    /** 获取客户端真实IP（支持Nginx代理） */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For可能包含多个IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}

