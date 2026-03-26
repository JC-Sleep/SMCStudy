package sys.smc.coupon.controller;

import com.google.common.util.concurrent.RateLimiter;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.coupon.dto.request.SeckillGrabRequest;
import sys.smc.coupon.dto.response.ApiResponse;
import sys.smc.coupon.dto.response.SeckillActivityDTO;
import sys.smc.coupon.dto.response.SeckillGrabResult;
import sys.smc.coupon.exception.SeckillException;
import sys.smc.coupon.service.SeckillService;
import sys.smc.coupon.util.DistributedRateLimiter;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀Controller
 * 
 * 包含三层限流保护:
 * 1. 全局限流(Guava): 单机保护
 * 2. IP限流(Redis): 防CC攻击
 * 3. 用户限流(Redis): 防刷
 */
@Api(tags = "秒杀抢券")
@Slf4j
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final RateLimiter seckillRateLimiter;
    private final DistributedRateLimiter distributedRateLimiter;

    @ApiOperation("抢购优惠券")
    @PostMapping("/grab")
    public ApiResponse<SeckillGrabResult> grabCoupon(
            @Validated @RequestBody SeckillGrabRequest request,
            HttpServletRequest httpRequest) {
        
        String userId = request.getUserId();
        String clientIp = getClientIp(httpRequest);

        // ========== 三层限流 ==========
        
        // 第1层: 全局限流(单机) - 每秒1万请求
        // 作用: 保护当前服务实例不被压垮
        if (!seckillRateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            log.warn("触发全局限流: userId={}, ip={}", userId, clientIp);
            throw SeckillException.systemBusy();
        }

        // 第2层: IP限流(分布式) - 每IP每秒100次
        // 作用: 防止单个IP(可能是攻击者)刷接口
        if (!distributedRateLimiter.tryAcquire("limit:ip:" + clientIp, 100, 1)) {
            log.warn("触发IP限流: userId={}, ip={}", userId, clientIp);
            throw SeckillException.rateLimitExceeded();
        }
        
        // 第3层: 用户限流(分布式) - 每用户每秒10次
        // 作用: 防止单个用户疯狂点击
        if (!distributedRateLimiter.tryAcquire("limit:user:" + userId, 10, 1)) {
            log.warn("触发用户限流: userId={}, ip={}", userId, clientIp);
            throw SeckillException.rateLimitExceeded();
        }
        
        // ========== 正常业务处理 ==========
        SeckillGrabResult result = seckillService.grabCoupon(request);
        return ApiResponse.success(result);
    }
    
    /**
     * 获取客户端真实IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    @ApiOperation("获取秒杀活动列表")
    @GetMapping("/activities")
    public ApiResponse<List<SeckillActivityDTO>> listActivities(
            @ApiParam("状态:0未开始,1进行中,3已结束") @RequestParam(required = false) Integer status) {
        List<SeckillActivityDTO> activities = seckillService.listActivities(status);
        return ApiResponse.success(activities);
    }

    @ApiOperation("获取活动详情")
    @GetMapping("/activity/{activityId}")
    public ApiResponse<SeckillActivityDTO> getActivityDetail(
            @ApiParam("活动ID") @PathVariable Long activityId) {
        SeckillActivityDTO activity = seckillService.getActivityDetail(activityId);
        return ApiResponse.success(activity);
    }

    @ApiOperation("获取活动库存状态")
    @GetMapping("/status/{activityId}")
    public ApiResponse<Map<String, Object>> getActivityStatus(
            @ApiParam("活动ID") @PathVariable Long activityId,
            @ApiParam("用户ID(可选)") @RequestParam(required = false) String userId) {
        Map<String, Object> status = new HashMap<>();
        status.put("activityId", activityId);
        status.put("remainStock", seckillService.getRemainStock(activityId));
        
        if (userId != null) {
            status.put("userGrabbed", seckillService.getUserGrabbedCount(activityId, userId));
        }
        
        return ApiResponse.success(status);
    }
}

