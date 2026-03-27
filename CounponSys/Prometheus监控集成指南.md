# Prometheus监控集成指南（可选增强）

> **文档状态**: 📋 可选方案（推荐使用）  
> **优势**: 轻量级监控，比Sentinel Dashboard更通用  
> **工作量**: 2小时

---

## 📋 为什么选择Prometheus而不是Sentinel？

### 对比分析

| 维度 | Prometheus + Grafana | Sentinel Dashboard |
|------|---------------------|-------------------|
| **轻量级** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **通用性** | 监控所有指标（JVM、DB、Redis等） | 只监控限流 |
| **学习成本** | 低（行业标准） | 中等 |
| **社区支持** | 极活跃 | 活跃 |
| **可视化** | Grafana（更强大） | Sentinel自带 |
| **运维成本** | 低（可接入现有监控） | 中（独立维护） |
| **侵入性** | 无侵入（只采集指标） | 需要Sentinel SDK |

**结论**：Prometheus更适合作为通用监控方案 ✅

---

## 🚀 快速集成（3步）

### Step 1: 添加依赖

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Spring Boot Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- Micrometer Prometheus -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

---

### Step 2: 配置Actuator

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus  # 暴露Prometheus端点
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}  # 应用名标签
```

---

### Step 3: 启用指标采集

**方式1：使用已创建的SeckillMetricsCollector**

文件已创建：`monitor/SeckillMetricsCollector.java`

功能：
- 每5秒采集活动库存、限流速率、利用率
- 每10秒采集限流统计

**方式2：Controller中记录请求（可选）**

```java
// SeckillController.java
@RestController
public class SeckillController {
    
    @Autowired
    private SeckillMetricsCollector metricsCollector;
    
    @PostMapping("/grab")
    public ApiResponse<SeckillGrabResult> grabCoupon(...) {
        metricsCollector.recordRequest();  // 记录总请求
        
        try {
            if (!dynamicRateLimiterManager.tryAcquire(activityId)) {
                metricsCollector.recordBlocked();  // 记录被限流
                throw SeckillException.systemBusy();
            }
            
            // 业务逻辑...
        } catch (Exception e) {
            // ...
        }
    }
}
```

---

## 📊 访问监控指标

### 1. 访问Prometheus端点

```bash
# 启动应用后访问
curl http://localhost:8090/actuator/prometheus

# 输出示例：
# HELP seckill_stock_remain 剩余库存
# TYPE seckill_stock_remain gauge
seckill_stock_remain{activity_id="1001",activity_name="春节秒杀",} 2500.0

# HELP seckill_rate_limiter_current_rate 当前限流速率
# TYPE seckill_rate_limiter_current_rate gauge
seckill_rate_limiter_current_rate{activity_id="1001",} 3750.0

# HELP seckill_stock_utilization_rate 库存利用率
# TYPE seckill_stock_utilization_rate gauge
seckill_stock_utilization_rate{activity_id="1001",} 75.0

# HELP seckill_rate_limit_block_rate 限流率
# TYPE seckill_rate_limit_block_rate gauge
seckill_rate_limit_block_rate 35.5
```

---

## 📈 Grafana可视化

### 1. 配置Prometheus

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'coupon-system'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8090']
```

---

### 2. Grafana Dashboard配置

#### 面板1：活动库存监控
```
指标：seckill_stock_remain
图表类型：时间序列图
说明：实时显示各活动的剩余库存
```

#### 面板2：限流速率监控
```
指标：seckill_rate_limiter_current_rate
图表类型：时间序列图
说明：显示动态限流速率的变化
```

#### 面板3：库存利用率
```
指标：seckill_stock_utilization_rate
图表类型：仪表盘
说明：显示库存已用百分比
```

#### 面板4：限流统计
```
指标：seckill_rate_limit_block_rate
图表类型：仪表盘
告警：>50%时触发告警
```

---

### 3. Grafana Dashboard JSON（示例）

```json
{
  "dashboard": {
    "title": "优惠券秒杀监控",
    "panels": [
      {
        "title": "剩余库存",
        "targets": [
          {
            "expr": "seckill_stock_remain",
            "legendFormat": "{{activity_name}}"
          }
        ],
        "type": "graph"
      },
      {
        "title": "限流速率",
        "targets": [
          {
            "expr": "seckill_rate_limiter_current_rate",
            "legendFormat": "活动{{activity_id}}"
          }
        ],
        "type": "graph"
      },
      {
        "title": "限流率",
        "targets": [
          {
            "expr": "seckill_rate_limit_block_rate"
          }
        ],
        "type": "gauge",
        "thresholds": "30,50"
      }
    ]
  }
}
```

---

## 🎯 效果展示

### Grafana Dashboard效果（模拟）

```
┌─────────────────────────────────────────────────────────────────┐
│  优惠券秒杀监控 Dashboard                                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────┐             │
│  │  剩余库存           │  │  限流速率           │             │
│  │  ┌───────────────┐  │  │  ┌───────────────┐  │             │
│  │  │ 活动1001: 2500│  │  │  │ 3750 QPS      │  │             │
│  │  │ 活动1002: 100 │  │  │  │ ▲ ▲ ▲ ▲       │  │             │
│  │  │ 活动1003: 0   │  │  │  │ ▲ ▲ ▲ ▲ ▲     │  │             │
│  │  └───────────────┘  │  │  └───────────────┘  │             │
│  └─────────────────────┘  └─────────────────────┘             │
│                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────┐             │
│  │  库存利用率         │  │  限流率             │             │
│  │  ┌───────────────┐  │  │  ┌───────────────┐  │             │
│  │  │     75%       │  │  │  │     35.5%     │  │             │
│  │  │  ███████░░░   │  │  │  │  ████░░░░░░   │  │             │
│  │  └───────────────┘  │  │  └───────────────┘  │             │
│  └─────────────────────┘  └─────────────────────┘             │
│                                                                 │
│  ┌───────────────────────────────────────────┐                │
│  │  QPS趋势                                  │                │
│  │  10000 ┤         ╭─╮                      │                │
│  │   8000 ┤      ╭──╯ ╰──╮                   │                │
│  │   6000 ┤   ╭──╯        ╰──╮               │                │
│  │   4000 ┤╭──╯              ╰──╮            │                │
│  │   2000 ┼╯                    ╰──          │                │
│  │      0 └──────────────────────────────────│                │
│  │        12:00  12:05  12:10  12:15         │                │
│  └───────────────────────────────────────────┘                │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📝 监控指标清单

### 核心指标

| 指标名 | 类型 | 说明 | 标签 |
|-------|------|------|------|
| `seckill_stock_remain` | Gauge | 剩余库存 | activity_id, activity_name |
| `seckill_rate_limiter_current_rate` | Gauge | 当前限流速率 | activity_id |
| `seckill_stock_utilization_rate` | Gauge | 库存利用率 | activity_id |
| `seckill_rate_limit_block_rate` | Gauge | 限流率 | - |
| `seckill_ongoing_activities` | Gauge | 进行中活动数 | - |

### 告警规则（Prometheus Alertmanager）

```yaml
# alert.rules.yml
groups:
  - name: seckill_alerts
    rules:
      # 限流率过高告警
      - alert: HighRateLimitBlockRate
        expr: seckill_rate_limit_block_rate > 50
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "秒杀限流率过高"
          description: "限流率{{ $value }}%，超过50%"
      
      # 库存告警
      - alert: LowStock
        expr: seckill_stock_remain < 100
        labels:
          severity: warning
        annotations:
          summary: "活动{{ $labels.activity_name }}库存不足"
          description: "剩余库存{{ $value }}"
```

---

## 🔧 使用步骤

### 1. 启用Prometheus

```bash
# 1. 添加依赖（已在上面）
# 2. 配置application.yml（已在上面）
# 3. 启动应用
mvn spring-boot:run

# 4. 访问Prometheus端点
curl http://localhost:8090/actuator/prometheus
```

---

### 2. 部署Prometheus Server

```bash
# 1. 下载Prometheus
wget https://github.com/prometheus/prometheus/releases/download/v2.40.0/prometheus-2.40.0.linux-amd64.tar.gz
tar -xzf prometheus-2.40.0.linux-amd64.tar.gz
cd prometheus-2.40.0.linux-amd64

# 2. 配置prometheus.yml（见上面）

# 3. 启动Prometheus
./prometheus --config.file=prometheus.yml

# 4. 访问
http://localhost:9090
```

---

### 3. 部署Grafana

```bash
# 1. 下载Grafana
wget https://dl.grafana.com/oss/release/grafana-9.3.0.linux-amd64.tar.gz
tar -xzf grafana-9.3.0.linux-amd64.tar.gz
cd grafana-9.3.0

# 2. 启动Grafana
./bin/grafana-server

# 3. 访问
http://localhost:3000
# 默认账号密码：admin/admin

# 4. 添加Prometheus数据源
# 配置 → Data Sources → Add → Prometheus
# URL: http://localhost:9090

# 5. 导入Dashboard
# 可以使用社区模板，或自定义
```

---

## 📊 监控面板示例

### Dashboard 1: 秒杀活动总览

**指标**：
- 进行中活动数量
- 各活动剩余库存
- 各活动限流速率
- 总QPS趋势

---

### Dashboard 2: 限流监控

**指标**：
- 限流率（%）
- 总请求数
- 被限流请求数
- 限流趋势图

---

### Dashboard 3: Kafka监控

**指标**：
- 消费者积压（来自KafkaLagMonitor）
- 消费速度
- 生产速度

---

## 🎯 优势总结

### vs Sentinel Dashboard

| 优势 | 说明 |
|------|------|
| **通用性** | 可以监控JVM、Redis、DB、Kafka，不只是限流 |
| **轻量级** | 无需Sentinel SDK，只需采集指标 |
| **灵活性** | 可以接入公司现有监控体系 |
| **成本低** | 开源免费，社区支持好 |

### vs 无监控

| 优势 | 说明 |
|------|------|
| **可视化** | 实时看到限流、库存、QPS趋势 |
| **告警** | 库存不足、限流率过高时自动告警 |
| **分析** | 历史数据分析，优化限流策略 |

---

## 📁 相关文件

### 已创建：
- ✅ `SeckillMetricsCollector.java` - 指标采集器

### 需要配置：
- ⚠️ `application.yml` - 添加actuator配置
- ⚠️ `pom.xml` - 添加prometheus依赖

---

## ✅ 实施清单

- [ ] 添加micrometer依赖
- [ ] 配置actuator暴露prometheus端点
- [ ] 启动应用，验证 `/actuator/prometheus` 可访问
- [ ] 部署Prometheus Server（可选，也可用现有）
- [ ] 部署Grafana（可选，也可用现有）
- [ ] 创建Grafana Dashboard
- [ ] 配置告警规则

**预计工作量**：2小时

---

## 🎯 最终建议

### 方案对比

| 方案 | 成本 | 收益 | 推荐度 |
|------|------|------|-------|
| **保持现状（只有日志）** | 0 | 低 | ⭐⭐⭐ |
| **添加Prometheus监控** | 2小时 | 高 | ⭐⭐⭐⭐⭐ |
| **迁移到Sentinel** | 6天 | 中 | ⭐⭐ |

**建议**：
1. **立即实施**：添加Prometheus监控（2小时）
2. **暂不实施**：迁移到Sentinel（成本高，收益低）

---

**🎉 Prometheus是最适合当前项目的监控方案！**

