package sys.smc.coupon.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sys.smc.coupon.dto.response.ApiResponse;
import sys.smc.coupon.monitor.DruidConnectionMonitor;

/**
 * 数据库连接池监控API
 * GET /api/monitor/db  → 查看连接池实时状态
 * Druid Web控制台      → http://localhost:8090/druid  (admin/admin123)
 */
@Api(tags = "DB连接监控")
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class DbMonitorController {

    private final DruidConnectionMonitor druidMonitor;

    @ApiOperation("查看DB连接池实时状态（活跃连接/等待线程/慢SQL入口）")
    @GetMapping("/db")
    public ApiResponse<DruidConnectionMonitor.PoolStats> getDbStatus() {
        DruidConnectionMonitor.PoolStats stats = druidMonitor.getPoolStats();
        return ApiResponse.success(stats);
    }
}

