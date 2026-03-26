s噶package sys.smc.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * 用于异步处理回调
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 回调处理线程池
     */
    @Bean("callbackExecutor")
    public Executor callbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数
        executor.setCorePoolSize(10);

        // 最大线程数
        executor.setMaxPoolSize(50);

        // 队列容量
        executor.setQueueCapacity(100);

        // 线程空闲时间
        executor.setKeepAliveSeconds(60);

        // 线程名前缀
        executor.setThreadNamePrefix("callback-");

        // 拒绝策略：由调用线程处理
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待任务完成后关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待时间
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return callbackExecutor();
    }
}

