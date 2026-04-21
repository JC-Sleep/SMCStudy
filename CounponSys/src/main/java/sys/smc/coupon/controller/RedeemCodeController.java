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
import javax.validation.constraints.*;
import java.util.Date;
import java.util.List;

/**
 * 兑换码 REST API
 *
 * POST /api/redeem-code/batch/generate  → 后台批量生成兑换码（需管理员Token）
 * POST /api/redeem-code/redeem          → 用户兑换（需用户Token）
 * POST /api/redeem-code/unlock/self     → 用户自助解锁（需用户Token）
 * POST /api/redeem-code/unlock/admin    → 客服/管理员解锁（需管理员Token）
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

    @ApiOperation("批量生成兑换码（管理后台，需管理员权限）")
    @PostMapping("/batch/generate")
    public ApiResponse<Long> generateBatch(@Valid @RequestBody GenerateRequest req) {
        Long batchId = redeemCodeService.generateBatch(
                req.getTemplateId(), req.getCount(), req.getExpireTime(),
                req.getBatchName(), req.getOperatorId());
        return ApiResponse.success("兑换码生成成功", batchId);
    }

    @ApiOperation("查询批次信息")
    @GetMapping("/batch/{batchId}")
    public ApiResponse<RedeemCodeBatch> getBatch(@ApiParam("批次ID") @PathVariable Long batchId) {
        return ApiResponse.success(redeemCodeService.getBatch(batchId));
    }

    @ApiOperation("查询批次下的兑换码列表（管理后台，生产环境限制访问）")
    @GetMapping("/batch/{batchId}/codes")
    public ApiResponse<List<CouponRedeemCode>> listCodes(@ApiParam("批次ID") @PathVariable Long batchId) {
        return ApiResponse.success(redeemCodeService.listByBatch(batchId));
    }

    // ======================== 用户端接口 ========================

    /**
     * 兑换码兑换
     *
     * 修复④：新增 requestId 参数实现幂等
     *   - 前端提交时生成UUID作为requestId
     *   - 网络超时重试时携带相同requestId
     *   - 服务端返回缓存结果，用户不会看到"已被使用"困惑提示
     */
    @ApiOperation("兑换码兑换（用户输入兑换码领券）")
    @PostMapping("/redeem")
    public ApiResponse<Long> redeem(
            @Valid @RequestBody RedeemRequest req,
            HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);
        Long couponId = redeemCodeService.redeem(
                req.getCode(), req.getUserId(), clientIp,
                req.getChannel() != null ? req.getChannel() : "APP",
                req.getRequestId()  // 修复④：传入requestId
        );

        // couponId=-1L 表示发券处理中（补偿任务已创建，用户稍后收到券）
        if (couponId == -1L) {
            return ApiResponse.success("兑换成功！优惠券正在发放，请稍后查看您的券包", couponId);
        }
        return ApiResponse.success("兑换成功！优惠券已发放到您的账户", couponId);
    }

    /**
     * 用户自助解锁（修复③）
     * 码被锁定（多次输错被系统锁定）可自助解锁一次
     * 超过2次解锁需联系客服人工审核
     */
    @ApiOperation("用户自助解锁兑换码（码被锁定时使用）")
    @PostMapping("/unlock/self")
    public ApiResponse<Void> selfUnlock(@Valid @RequestBody UnlockRequest req) {
        redeemCodeService.selfUnlock(req.getCode(), req.getUserId());
        return ApiResponse.success("解锁成功，您可以重新尝试兑换", null);
    }

    /**
     * 管理员/客服解锁（修复③）
     * 需要管理员权限（JWT Role=ADMIN 或 CUSTOMER_SERVICE）
     * STATUS=1（已成功兑换）的码绝对禁止解锁
     */
    @ApiOperation("管理员解锁兑换码（客服后台操作）")
    @PostMapping("/unlock/admin")
    public ApiResponse<Void> adminUnlock(@Valid @RequestBody AdminUnlockRequest req) {
        redeemCodeService.adminUnlock(req.getCode(), req.getOperatorId(), req.getReason());
        return ApiResponse.success("解锁成功", null);
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

        /** 每用户可兑换上限（默认1张，修复②同批次重复兑换） */
        private Integer maxPerUser;
    }

    @Data
    public static class RedeemRequest {
        @NotBlank(message = "兑换码不能为空")
        private String code;

        @NotBlank(message = "用户ID不能为空")
        private String userId;

        /** 兑换渠道: APP / WEB / H5 */
        private String channel;

        /**
         * 幂等ID（修复④：前端UUID，防网络重试重复发券）
         * 前端必须在每次"提交"时生成新UUID，重试时使用相同UUID
         * 示例：requestId = UUID.randomUUID().toString()
         */
        private String requestId;
    }

    @Data
    public static class UnlockRequest {
        @NotBlank(message = "兑换码不能为空")
        private String code;

        @NotBlank(message = "用户ID不能为空")
        private String userId;
    }

    @Data
    public static class AdminUnlockRequest {
        @NotBlank(message = "兑换码不能为空")
        private String code;

        @NotBlank(message = "操作人不能为空")
        private String operatorId;

        @NotBlank(message = "解锁原因不能为空（审计必填）")
        private String reason;
    }

    // ======================== 工具方法 ========================

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}

