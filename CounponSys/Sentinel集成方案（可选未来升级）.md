# Sentinel集成方案（可选未来升级）

> **文档状态**: 📋 备选方案（当前不实施）  
> **实施条件**: QPS超过10万 或 需要可视化面板 或 需要熔断降级  
> **预计工作量**: 6个工作日

---

## 📋 什么时候考虑Sentinel？

### 触发条件（满足任一即可）

| 条件 | 说明 | 当前状态 |
|------|------|---------|
| QPS超过10万 | 需要全局精确限流 | ❌ 当前5-10万 |
| 需要可视化 | 运营需要实时监控面板 | ❌ 当前日志够用 |
| 需要熔断降级 | 依赖服务不稳定 | ❌ 当前服务稳定 |
| 多微服务管理 | 有多个服务需要统一限流 | ❌ 当前单服务 |

**结论**：当前不需要Sentinel ✅

---

## 🔄 集成步骤（如果未来需要）

### Step 1: 部署Sentinel Dashboard

```bash
# 1. 下载Dashboard
wget https://github.com/alibaba/Sentinel/releases/download/1.8.6/sentinel-dashboard-1.8.6.jar

# 2. 启动Dashboard
java -Dserver.port=8858 \
     -Dcsp.sentinel.dashboard.server=localhost:8858 \
     -jar sentinel-dashboard-1.8.6.jar

# 3. 访问
http://localhost:8858
# 默认账号密码：sentinel/sentinel
```

---

### Step 2: 添加依赖

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Sentinel核心 -->
    <dependency>
        <groupId>com.alibaba.csp</groupId>
        <artifactId>sentinel-core</artifactId>
        <version>1.8.6</version>
    </dependency>
    
    <!-- Sentinel注解支持 -->
    <dependency>
        <groupId>com.alibaba.csp</groupId>
        <artifactId>sentinel-annotation-aspectj</artifactId>
        <version>1.8.6</version>
    </dependency>
    
    <!-- Sentinel Dashboard通信 -->
    <dependency>
        <groupId>com.alibaba.csp</groupId>
        <artifactId>sentinel-transport-simple-http</artifactId>
        <version>1.8.6</version>
    </dependency>
    
    <!-- 可选：规则持久化到Nacos -->
    <dependency>
        <groupId>com.alibaba.csp</groupId>
        <artifactId>sentinel-datasource-nacos</artifactId>
        <version>1.8.6</version>
    </dependency>
</dependencies>
```

---

### Step 3: 配置Sentinel

```yaml
# application.yml
spring:
  application:
    name: coupon-system
  
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8858  # Dashboard地址
        port: 8719                 # 与Dashboard通信端口
      
      # 饥饿加载（启动时就连接Dashboard）
      eager: true
      
      # 规则持久化（可选）
      datasource:
        flow:
          nacos:
            server-addr: localhost:8848
            dataId: coupon-flow-rules
            groupId: SENTINEL_GROUP
            rule-type: flow
```

---

### Step 4: 配置限流规则

#### 方式1：代码配置（简单）

```java
@Configuration
public class SentinelConfig {
    
    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        
        // 秒杀抢购限流
        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource("seckill_grab");
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(10000);  // 10000 QPS
        seckillRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        rules.add(seckillRule);
        
        FlowRuleManager.loadRules(rules);
    }
    
    @PostConstruct
    public void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        
        // 积分服务熔断
        DegradeRule pointsRule = new DegradeRule();
        pointsRule.setResource("deduct_points");
        pointsRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        pointsRule.setCount(0.5);  // 异常比例50%
        pointsRule.setTimeWindow(10);  // 熔断10秒
        rules.add(pointsRule);
        
        DegradeRuleManager.loadRules(rules);
    }
}
```

#### 方式2：Dashboard配置（推荐）

直接在Dashboard界面配置，实时推送到应用

---

### Step 5: 改造Controller

#### 方式1：注解方式（推荐）

```java
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {
    
    @SentinelResource(
        value = "seckill_grab",
        blockHandler = "handleBlock",      // 限流处理
        fallback = "handleFallback"        // 异常降级
    )
    @PostMapping("/grab")
    public ApiResponse<SeckillGrabResult> grabCoupon(
            @RequestBody SeckillGrabRequest request) {
        
        // 不需要手动限流判断，Sentinel自动处理
        return ApiResponse.success(seckillService.grabCoupon(request));
    }
    
    // 限流处理方法
    public ApiResponse<SeckillGrabResult> handleBlock(
            SeckillGrabRequest request, 
            BlockException e) {
        log.warn("触发限流: userId={}", request.getUserId());
        return ApiResponse.fail(503, "系统繁忙，请稍后重试");
    }
    
    // 异常降级方法
    public ApiResponse<SeckillGrabResult> handleFallback(
            SeckillGrabRequest request, 
            Throwable e) {
        log.error("服务异常: userId={}", request.getUserId(), e);
        return ApiResponse.fail(500, "服务异常");
    }
}
```

#### 方式2：编程方式

```java
@PostMapping("/grab")
public ApiResponse<SeckillGrabResult> grabCoupon(
        @RequestBody SeckillGrabRequest request) {
    
    Entry entry = null;
    try {
        // 获取Sentinel Entry
        entry = SphU.entry("seckill_grab");
        
        // 业务逻辑
        return ApiResponse.success(seckillService.grabCoupon(request));
        
    } catch (BlockException e) {
        // 被限流
        log.warn("触发限流: userId={}", request.getUserId());
        return ApiResponse.fail(503, "系统繁忙，请稍后重试");
        
    } finally {
        if (entry != null) {
            entry.exit();
        }
    }
}
```

---

### Step 6: 热点参数限流（强大功能）

```java
// 配置：针对不同活动ID限流
@PostConstruct
public void initParamFlowRules() {
    ParamFlowRule rule = new ParamFlowRule("seckill_grab")
        .setParamIdx(0)  // 第0个参数（activityId）
        .setGrade(RuleConstant.FLOW_GRADE_QPS)
        .setCount(1000);  // 默认每个活动1000 QPS
    
    // 特殊活动单独限流
    rule.setParamFlowItemList(Arrays.asList(
        new ParamFlowItem().setObject("1001").setCount(5000),  // 大活动
        new ParamFlowItem().setObject("1002").setCount(500)    // 小活动
    ));
    
    ParamFlowRuleManager.loadRules(Collections.singletonList(rule));
}
```

---

## 9. 性能对比

### 9.1 限流性能测试

| 方案 | QPS | 延迟(P99) | CPU占用 | 内存占用 |
|------|-----|----------|---------|---------|
| **Guava** | 10万+ | <1ms | 5% | 100MB |
| **Sentinel（嵌入）** | 10万+ | <2ms | 8% | 200MB |
| **Sentinel（Token Server）** | 8万+ | <5ms | 10% | 300MB |

**结论**：Guava性能最优 ✅

---

### 9.2 功能对比

| 功能 | Guava | Sentinel | 差距 |
|------|-------|----------|------|
| 基础限流 | ✅ | ✅ | 相同 |
| 动态限流 | ✅（已优化） | ✅ | 相同 |
| 集群限流 | ❌ | ✅ | Sentinel胜 |
| 熔断降级 | ❌ | ✅ | Sentinel胜 |
| 可视化 | ❌ | ✅ | Sentinel胜 |
| 热点限流 | ❌ | ✅ | Sentinel胜 |
| 系统保护 | ❌ | ✅ | Sentinel胜 |

---

## 10. 实施决策树

```
是否需要Sentinel？
    │
    ├─ QPS > 10万？
    │    ├─ 是 → 考虑Sentinel
    │    └─ 否 → 继续
    │
    ├─ 需要可视化监控？
    │    ├─ 是 → 考虑Sentinel
    │    └─ 否 → 继续
    │
    ├─ 需要熔断降级？
    │    ├─ 是 → 考虑Sentinel
    │    └─ 否 → 继续
    │
    ├─ 需要全局限流？
    │    ├─ 是 → 考虑Sentinel
    │    └─ 否 → 继续
    │
    └─ 当前Guava满足需求？
         ├─ 是 → ✅ 保持Guava
         └─ 否 → 考虑Sentinel
```

---

## 11. 最佳实践建议

### 11.1 保持Guava + 增强监控

```java
// 添加Prometheus监控
@Component
public class SeckillMetrics {
    
    private final MeterRegistry meterRegistry;
    private final DynamicRateLimiterManager rateLimiterManager;
    
    @Scheduled(fixedRate = 1000)
    public void recordMetrics() {
        // 记录限流速率
        meterRegistry.gauge("seckill.rate_limit.current", 
            rateLimiterManager.getCurrentRate(activityId));
        
        // 记录剩余库存
        meterRegistry.gauge("seckill.stock.remain", 
            seckillService.getRemainStock(activityId));
    }
}

// Grafana可视化
// 无需Sentinel，使用开源监控栈
```

### 11.2 添加限流日志

```java
@Aspect
@Component
public class RateLimiterLogger {
    
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong blockedRequests = new AtomicLong(0);
    
    @Around("execution(* sys.smc.coupon.controller.SeckillController.grabCoupon(..))")
    public Object logRateLimit(ProceedingJoinPoint pjp) throws Throwable {
        totalRequests.incrementAndGet();
        
        try {
            return pjp.proceed();
        } catch (SeckillException e) {
            if (e.getCode() == 503) {  // 限流异常
                blockedRequests.incrementAndGet();
            }
            throw e;
        }
    }
    
    @Scheduled(fixedRate = 10000)
    public void logStats() {
        long total = totalRequests.getAndSet(0);
        long blocked = blockedRequests.getAndSet(0);
        
        if (total > 0) {
            double blockRate = (double) blocked / total * 100;
            log.info("限流统计: 总请求={}, 被限流={}, 限流率={:.2f}%", 
                    total, blocked, blockRate);
        }
    }
}
```

---

## 12. 结论

### 当前最佳方案：Guava + 动态限流 ⭐⭐⭐⭐⭐

**保持理由**：
1. ✅ 性能优秀（10万+ QPS）
2. ✅ 已实现动态调整
3. ✅ 运维成本低
4. ✅ 满足业务需求

**可选增强**：
- Prometheus监控
- 限流日志
- Grafana可视化

**未来升级路径**：
- 当QPS超过10万时
- 或需要可视化面板时
- 或需要熔断降级时
- 再考虑迁移到Sentinel

---

**🎯 建议：保持当前方案，持续优化，按需升级！**

