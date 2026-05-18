# XXL-Job 快速实施指南

## 一、环境准备（30分钟）

### 1. 数据库准备
```sql
-- 创建数据库
CREATE DATABASE xxl_job DEFAULT CHARACTER SET utf8mb4;

-- 执行官方SQL脚本
-- 下载：https://github.com/xuxueli/xxl-job/blob/master/doc/db/tables_xxl_job.sql
```

### 2. 下载 XXL-Job Admin
```bash
# 方式1：使用Docker（推荐）
docker pull xuxueli/xxl-job-admin:2.4.0

docker run -d \
  --name xxl-job-admin \
  -p 8080:8080 \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8 \
  --spring.datasource.username=root \
  --spring.datasource.password=yourpassword" \
  xuxueli/xxl-job-admin:2.4.0

# 方式2：下载JAR包运行
# 下载地址：https://github.com/xuxueli/xxl-job/releases
java -jar xxl-job-admin-2.4.0.jar
```

### 3. 访问管理界面
```
URL: http://localhost:8080/xxl-job-admin
默认账号：admin
默认密码：123456
```

---

## 二、业务应用集成（15分钟/应用）

### 1. 添加Maven依赖
```xml
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
    <version>2.4.0</version>
</dependency>
```

### 2. 配置文件（application.yml）
```yaml
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:8080/xxl-job-admin  # Admin地址
    executor:
      appname: order-service  # 执行器名称（唯一标识）
      ip:  # 为空则自动获取
      port: 9999  # 执行器端口
      logpath: /data/applogs/xxl-job/jobhandler  # 日志路径
      logretentiondays: 30  # 日志保留天数
    accessToken: default_token  # 访问令牌（生产环境必须修改）
```

### 3. 配置类
```java
package com.yourcompany.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {
    
    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;
    
    @Value("${xxl.job.executor.appname}")
    private String appname;
    
    @Value("${xxl.job.executor.ip:}")
    private String ip;
    
    @Value("${xxl.job.executor.port}")
    private int port;
    
    @Value("${xxl.job.executor.logpath}")
    private String logPath;
    
    @Value("${xxl.job.executor.logretentiondays}")
    private int logRetentionDays;
    
    @Value("${xxl.job.accessToken}")
    private String accessToken;
    
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        xxlJobSpringExecutor.setAdminAddresses(adminAddresses);
        xxlJobSpringExecutor.setAppname(appname);
        xxlJobSpringExecutor.setIp(ip);
        xxlJobSpringExecutor.setPort(port);
        xxlJobSpringExecutor.setAccessToken(accessToken);
        xxlJobSpringExecutor.setLogPath(logPath);
        xxlJobSpringExecutor.setLogRetentionDays(logRetentionDays);
        return xxlJobSpringExecutor;
    }
}
```

### 4. 编写任务Handler
```java
package com.yourcompany.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderJobHandler {
    
    @Autowired
    private OrderService orderService;  // 复用业务Service
    
    /**
     * 订单超时自动关闭
     */
    @XxlJob("orderTimeoutCloseJob")
    public void closeTimeoutOrders() {
        XxlJobHelper.log("开始处理超时订单...");
        
        try {
            int count = orderService.closeTimeoutOrders();
            XxlJobHelper.log("成功关闭{}个超时订单", count);
        } catch (Exception e) {
            XxlJobHelper.log("处理失败：{}", e.getMessage());
            throw e;  // 抛出异常，任务标记为失败
        }
    }
    
    /**
     * 订单自动确认收货
     */
    @XxlJob("orderAutoConfirmJob")
    public void autoConfirmOrders() {
        XxlJobHelper.log("开始自动确认收货...");
        orderService.autoConfirmReceipt();
    }
    
    /**
     * 分片任务示例（处理大数据量）
     */
    @XxlJob("orderBatchProcessJob")
    public void batchProcess() {
        // 获取分片参数
        int shardIndex = XxlJobHelper.getShardIndex();  // 当前分片索引（从0开始）
        int shardTotal = XxlJobHelper.getShardTotal();  // 总分片数
        
        XxlJobHelper.log("分片任务 - 当前分片：{}/{}", shardIndex, shardTotal);
        
        // 根据分片处理数据
        // 例如：SELECT * FROM orders WHERE id % #{shardTotal} = #{shardIndex}
        orderService.processBatch(shardIndex, shardTotal);
    }
}
```

---

## 三、Admin控制台配置（5分钟）

### 1. 添加执行器
```
菜单：执行器管理 -> 新增执行器
- AppName: order-service（与配置文件一致）
- 名称：订单服务
- 注册方式：自动注册
```

### 2. 添加任务
```
菜单：任务管理 -> 新增任务

基础配置：
- 执行器：order-service
- 任务描述：订单超时自动关闭
- JobHandler：orderTimeoutCloseJob（与@XxlJob注解一致）

调度配置：
- 调度类型：Cron
- Cron表达式：0 */10 * * * ?（每10分钟执行一次）

高级配置：
- 路由策略：轮询（多实例负载均衡）
- 阻塞处理策略：单机串行
- 失败重试次数：3
- 报警邮件：your-email@company.com
```

### 3. 启动任务
```
点击"操作"列的"启动"按钮
```

---

## 四、迁移现有Cron任务

### 迁移对照表

| Linux Cron | XXL-Job |
|-----------|---------|
| `0 2 * * * /path/to/script.sh` | Cron: `0 0 2 * * ?` |
| `*/5 * * * * /path/to/script.sh` | Cron: `0 */5 * * * ?` |
| `0 0 * * 0 /path/to/script.sh` | Cron: `0 0 0 ? * SUN` |

**注意：XXL-Job的Cron是6位（秒 分 时 日 月 周），Linux是5位**

### 迁移步骤

#### Step 1: 梳理现有任务
```bash
# 导出所有cron任务
crontab -l > cron_backup.txt

# 整理成表格
# 任务名 | Cron表达式 | 执行脚本 | 所属业务 | 优先级
```

#### Step 2: 改造任务代码
```java
// 原来的Shell脚本逻辑
// #!/bin/bash
// mysql -e "UPDATE orders SET status='CLOSED' WHERE ..."

// 改造为Java代码
@XxlJob("orderCloseBySqlJob")
public void closeOrdersBySql() {
    jdbcTemplate.update(
        "UPDATE orders SET status=? WHERE timeout < NOW()",
        "CLOSED"
    );
}
```

#### Step 3: 在Admin中创建任务

#### Step 4: 灰度验证
```
1. 保留原Cron任务（暂停执行）
2. 启动XXL-Job任务
3. 观察1-2天，确认无问题
4. 删除原Cron任务
```

---

## 五、高级功能

### 1. 任务依赖（父子任务）
```
场景：数据同步任务完成后，触发报表生成任务

配置：
- 父任务：dataSyncJob
- 子任务：reportGenerateJob（在父任务配置中添加）
```

### 2. 分布式分片
```java
@XxlJob("bigDataProcessJob")
public void processBigData() {
    int shardIndex = XxlJobHelper.getShardIndex();
    int shardTotal = XxlJobHelper.getShardTotal();
    
    // 每个实例处理一部分数据
    List<Data> data = dataService.getDataByMod(shardIndex, shardTotal);
    data.forEach(this::process);
}
```

**应用场景：**
- 处理千万级数据
- 多实例并行处理
- 自动负载均衡

### 3. 动态传参
```
Admin配置：任务参数 = {"date":"2024-01-01","type":"order"}

任务代码：
@XxlJob("dynamicParamJob")
public void execute() {
    String param = XxlJobHelper.getJobParam();
    JSONObject json = JSON.parseObject(param);
    String date = json.getString("date");
    String type = json.getString("type");
    
    // 根据参数处理
}
```

---

## 六、监控告警

### 1. 邮件告警配置
```properties
# application.properties（Admin端）
spring.mail.host=smtp.qq.com
spring.mail.port=25
spring.mail.username=your-email@qq.com
spring.mail.password=your-password
spring.mail.from=your-email@qq.com
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

### 2. 企业微信/钉钉告警
```java
// 自定义告警Handler
@Component
public class DingTalkAlarmHandler implements JobAlarm {
    @Override
    public boolean doAlarm(XxlJobInfo info, XxlJobLog jobLog) {
        // 发送钉钉消息
        String msg = String.format(
            "任务执行失败\n任务名：%s\n失败原因：%s",
            info.getJobDesc(), jobLog.getTriggerMsg()
        );
        dingTalkService.send(msg);
        return true;
    }
}
```

### 3. Prometheus监控
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'xxl-job'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

---

## 七、生产环境部署

### 1. Admin高可用部署
```yaml
# docker-compose.yml
version: '3'
services:
  xxl-job-admin-1:
    image: xuxueli/xxl-job-admin:2.4.0
    ports:
      - "8081:8080"
    environment:
      PARAMS: "--spring.datasource.url=jdbc:mysql://mysql:3306/xxl_job"
  
  xxl-job-admin-2:
    image: xuxueli/xxl-job-admin:2.4.0
    ports:
      - "8082:8080"
    environment:
      PARAMS: "--spring.datasource.url=jdbc:mysql://mysql:3306/xxl_job"
  
  nginx:
    image: nginx
    ports:
      - "8080:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
```

**Nginx配置：**
```nginx
upstream xxl-job-admin {
    server xxl-job-admin-1:8080;
    server xxl-job-admin-2:8080;
}

server {
    listen 80;
    location / {
        proxy_pass http://xxl-job-admin;
    }
}
```

### 2. 数据库主从
```yaml
主库：负责写入（任务调度记录）
从库：负责查询（执行日志、统计）
```

### 3. 安全配置
```properties
# 修改默认密码
# 配置访问令牌
xxl.job.accessToken=your-secret-token-here

# 配置IP白名单（可选）
xxl.job.executor.ip.whitelist=192.168.1.0/24
```

---

## 八、常见问题

### Q1: 任务不执行？
**检查清单：**
- [ ] 执行器是否注册成功（执行器管理 -> 在线机器）
- [ ] 任务是否启动（任务状态是否为"运行中"）
- [ ] Cron表达式是否正确
- [ ] JobHandler名称是否与@XxlJob注解一致

### Q2: 如何控制任务并发？
```
阻塞处理策略：
- 单机串行：同一任务同一实例不并发
- 丢弃后续调度：上次未完成则丢弃本次
- 覆盖之前调度：停止上次执行，开始本次
```

### Q3: 如何手动触发任务？
```
任务列表 -> 操作 -> 执行一次
```

### Q4: 如何查看执行日志？
```
任务列表 -> 操作 -> 查询 -> 执行日志
点击"执行日志"可以看到实时输出
```

---

## 九、成本估算

### 资源需求（1000个任务）

| 组件 | 实例数 | CPU | 内存 | 存储 |
|-----|-------|-----|------|------|
| Admin | 2 | 2核 | 4GB | 20GB |
| MySQL | 1主1从 | 4核 | 8GB | 100GB |
| 执行器 | 分布在各业务应用 | - | +10MB/应用 | - |

**总成本：** 约 ¥2000/月（阿里云ECS）

---

## 十、学习资源

- 官方文档：https://www.xuxueli.com/xxl-job/
- GitHub：https://github.com/xuxueli/xxl-job
- 社区示例：https://github.com/xuxueli/xxl-job/tree/master/xxl-job-executor-samples

---

## 总结

**从Cron迁移到XXL-Job的ROI：**
- ✅ 实施成本：2-4周
- ✅ 维护成本降低：70%
- ✅ 故障排查效率提升：80%
- ✅ 投资回报周期：3个月

**立即开始：**
1. 今天：部署Admin + 集成第一个执行器
2. 本周：迁移10个高优先级任务
3. 本月：迁移100个任务
4. 3个月：完成全部迁移

**祝你迁移顺利！🎉**

