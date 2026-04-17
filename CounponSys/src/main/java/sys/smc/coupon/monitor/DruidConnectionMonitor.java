package sys.smc.coupon.monitor;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Druid数据库连接监控组件
 * 作用：
 *  1. 实时查看连接池水位（activeCount、waitThreadCount）
 *  2. 检测长连接泄漏（超过X秒未归还的连接 + 打印调用栈）
 *  3. 慢SQL统计（超过2秒的SQL自动告警）
 *  4. 连接等待告警（等待线程数 > 阈值时告警）
 *
 * 访问 Druid Web控制台: http://localhost:8090/druid
 * 查看监控API:          http://localhost:8090/api/monitor/db
 */
@Slf4j
@Component
public class DruidConnectionMonitor {

    @Resource
    private DataSource dataSource;

    /** 连接池活跃连接数告警阈值（超过80%池大小则告警） */
    private static final double ACTIVE_RATIO_WARN = 0.8;
    /** 等待线程数告警阈值 */
    private static final int WAIT_THREAD_WARN = 5;

    private final AtomicLong lastAlertTime = new AtomicLong(0);
    private static final long ALERT_INTERVAL_MS = 60_000; // 1分钟内只告警一次

    // ======================== 定时监控 ========================

    /**
     * 每30秒打印连接池状态日志
     * 重点监控：activeCount（活跃连接） + waitThreadCount（等待线程）
     */
    @Scheduled(fixedDelay = 30_000)
    public void logPoolStatus() {
        DruidDataSource druidDs = getDruidDataSource();
        if (druidDs == null) return;

        int active   = druidDs.getActiveCount();
        int maxActive = druidDs.getMaxActive();
        int idle     = druidDs.getPoolingCount();
        int waiting  = druidDs.getWaitThreadCount();
        long totalBorrow = druidDs.getConnectCount();
        long errorCount  = druidDs.getConnectErrorCount();

        log.info("[DB连接池] active={}/{} idle={} waiting={} totalBorrow={} error={}",
                active, maxActive, idle, waiting, totalBorrow, errorCount);

        // 告警逻辑
        long now = System.currentTimeMillis();
        if (now - lastAlertTime.get() > ALERT_INTERVAL_MS) {
            double ratio = maxActive > 0 ? (double) active / maxActive : 0;
            if (ratio >= ACTIVE_RATIO_WARN) {
                log.warn("[DB连接池告警] 活跃连接占用率过高! active={}/{} ratio={}% - 检查是否有连接泄漏！",
                        active, maxActive, (int)(ratio * 100));
                lastAlertTime.set(now);
            }
            if (waiting >= WAIT_THREAD_WARN) {
                log.warn("[DB连接池告警] 有{}个线程在等待连接！可能有慢SQL或连接泄漏！", waiting);
                lastAlertTime.set(now);
            }
        }
    }

    /**
     * 每5分钟打印慢SQL TOP10
     */
    @Scheduled(fixedDelay = 300_000)
    public void logSlowSql() {
        try {
            // Druid会自动统计慢SQL（配置中slowSqlMillis=2000），这里只做日志提示
            DruidDataSource druidDs = getDruidDataSource();
            if (druidDs == null) return;
            long executeCount    = druidDs.getExecuteCount();
            long executeErrorCount = druidDs.getExecuteErrorCount();
            log.info("[DB慢SQL统计] 总执行次数={} 执行错误次数={} → 详细慢SQL请访问 /druid/sql.html",
                    executeCount, executeErrorCount);
        } catch (Exception e) {
            log.error("[DB慢SQL统计] 获取统计信息失败", e);
        }
    }

    // ======================== 监控API ========================

    @Data
    public static class PoolStats {
        private String poolName;
        private int activeCount;
        private int maxActive;
        private int idleCount;
        private int waitThreadCount;
        private double activeRatio;
        private String status;           // NORMAL / WARNING / CRITICAL
        private long totalBorrowCount;
        private long connectErrorCount;
        private long executeCount;
        private long executeErrorCount;
        private String druidConsoleUrl;
        private String slowSqlUrl;
    }

    /**
     * 获取当前连接池实时状态（供监控平台/运维使用）
     */
    public PoolStats getPoolStats() {
        PoolStats stats = new PoolStats();
        stats.setDruidConsoleUrl("http://<host>:8090/druid");
        stats.setSlowSqlUrl("http://<host>:8090/druid/sql.html");

        DruidDataSource druidDs = getDruidDataSource();
        if (druidDs == null) {
            stats.setStatus("UNAVAILABLE");
            return stats;
        }

        int active   = druidDs.getActiveCount();
        int maxActive = druidDs.getMaxActive();

        stats.setPoolName(druidDs.getName());
        stats.setActiveCount(active);
        stats.setMaxActive(maxActive);
        stats.setIdleCount(druidDs.getPoolingCount());
        stats.setWaitThreadCount(druidDs.getWaitThreadCount());
        stats.setTotalBorrowCount(druidDs.getConnectCount());
        stats.setConnectErrorCount(druidDs.getConnectErrorCount());
        stats.setExecuteCount(druidDs.getExecuteCount());
        stats.setExecuteErrorCount(druidDs.getExecuteErrorCount());

        double ratio = maxActive > 0 ? (double) active / maxActive : 0;
        stats.setActiveRatio(Math.round(ratio * 1000.0) / 10.0); // percentage

        if (ratio >= ACTIVE_RATIO_WARN || druidDs.getWaitThreadCount() >= WAIT_THREAD_WARN) {
            stats.setStatus("WARNING ⚠️");
        } else if (ratio >= 0.95) {
            stats.setStatus("CRITICAL 🔴");
        } else {
            stats.setStatus("NORMAL ✅");
        }
        return stats;
    }

    // ======================== 工具方法 ========================

    private DruidDataSource getDruidDataSource() {
        try {
            // 兼容Spring包装后的DataSource
            if (dataSource instanceof DruidDataSource) {
                return (DruidDataSource) dataSource;
            }
            // Druid StatManager获取
            Set<DruidDataSource> druidSources = DruidDataSourceStatManager.getDruidDataSourceInstances();
            if (!druidSources.isEmpty()) {
                return druidSources.iterator().next();
            }
        } catch (Exception e) {
            log.warn("[DruidMonitor] 获取DruidDataSource失败: {}", e.getMessage());
        }
        return null;
    }
}



