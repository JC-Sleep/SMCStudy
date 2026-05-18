package sys.smc.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 *
 * 修复点：
 *  1. 修复文件头乱码
 *  2. 实现 AsyncUncaughtExceptionHandler：@Async void 方法抛出的异常
 *     默认会被吞掉，必须在这里捕获并告警，否则静默丢失
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 回调处理线程池
     *
     * 参数说明（生产调优建议）：
     *   corePoolSize=10  → 常驻10线程处理正常流量
     *   maxPoolSize=50   → 峰值时最多扩到50线程
     *   queueCapacity=100→ 队列最多积压100个任务
     *   CallerRunsPolicy → 队列满时由HTTP线程直接处理（阻塞调用方，自动限流，不丢任务）
     *   WaitForShutdown  → 优雅关闭：等待已入队任务完成（最多60秒）
     */
    @Bean("callbackExecutor")
    public Executor callbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("callback-");

        // 拒绝策略：由调用者线程执行（HTTP线程），起到背压效果，不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 优雅关闭：等待任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        log.info("回调线程池初始化完成：core={}, max={}, queue={}",
            executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return callbackExecutor();
    }

    /**
     * ⭐ 异步未捕获异常处理器
     *
     * 背景：@Async 标注的方法如果返回 void，其内部抛出的异常
     *       不会传播到调用方，默认会被 Spring 静默吞掉！
     *       必须在这里注册处理器，否则异常无声消失，无任何日志。
     *
     * 本处理器会：
     *   1. 打印 ERROR 日志（含方法名、参数、异常堆栈）
     *   2. 生产环境应在此触发告警（钉钉/邮件/PagerDuty）
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new PaymentAsyncExceptionHandler();
    }

    /**
     * 自定义异步异常处理器
     */
    private static class PaymentAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

        private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PaymentAsyncExceptionHandler.class);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error(
                "【异步任务异常】方法：{}.{}，参数：{}，异常：{}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                Arrays.toString(params),
                ex.getMessage(),
                ex
            );
            // TODO（生产）: 接入告警系统，如钉钉机器人/邮件/Prometheus alertmanager
            // AlertService.sendAlert("async-exception", method.getName(), ex);
        }
    }
}


