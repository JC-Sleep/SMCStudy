package sys.smc.payment.config;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Snowflake ID 生成器配置
 *
 * ② 修复：每个实例必须使用不同的 workerId，防止分布式部署时 ID 冲突
 *
 * 三个实例启动命令：
 *   实例A: java -jar payment.jar -DSNOWFLAKE_WORKER_ID=1 -DSNOWFLAKE_DC_ID=1
 *   实例B: java -jar payment.jar -DSNOWFLAKE_WORKER_ID=2 -DSNOWFLAKE_DC_ID=1
 *   实例C: java -jar payment.jar -DSNOWFLAKE_WORKER_ID=3 -DSNOWFLAKE_DC_ID=1
 *
 * workerId 范围：0~31（5位），datacenterId 范围：0~31（5位）
 */
@Slf4j
@Configuration
public class SnowflakeConfig {

    @Value("${snowflake.worker-id:0}")
    private long workerId;

    @Value("${snowflake.datacenter-id:0}")
    private long datacenterId;

    @Bean
    public Snowflake snowflake() {
        if (workerId == 0 && datacenterId == 0) {
            log.warn("⚠️  Snowflake workerId=0, datacenterId=0（默认值）。" +
                     "单机开发可以忽略，分布式部署必须为每个实例设置不同的 SNOWFLAKE_WORKER_ID！");
        }
        Snowflake snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        log.info("Snowflake ID生成器初始化：workerId={}, datacenterId={}", workerId, datacenterId);
        return snowflake;
    }
}

