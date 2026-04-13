# Nginx 配置指南 — 支付系统三实例部署

---

## 什么是 Nginx？用大白话说

> Nginx 是一个"门卫 + 交通指挥员"。它站在你所有服务器的最前面，负责：
> 1. **接待所有用户请求**（门卫角色）
> 2. **把请求分发给后面多台服务器**（交通指挥员角色）
> 3. **某台服务器宕机时，自动不往那台发**（健康检查）

```
用户 → nginx → 实例A（8081）
            → 实例B（8082）  ← 轮流分发，每台各承担 1/3 流量
            → 实例C（8083）
```

### 为什么要用 Nginx？

| 没有 Nginx | 有 Nginx |
|-----------|---------|
| 所有请求打一台服务器，很快超载 | 流量均摊到3台，每台只承担1/3 |
| 一台宕机，用户直接访问不了 | 宕机自动切换，用户无感知 |
| 只有一个IP对外，不好管理 | 统一入口 IP，后端透明扩展 |
| 没有SSL证书统一管理 | Nginx统一处理 HTTPS，后端只用 HTTP |

---

## 完整 Nginx 配置（修复⑧ 含健康检查）

### 文件路径：`/etc/nginx/conf.d/payment.conf`

```nginx
# ============================================================
# 支付系统 Nginx 配置 v2.1
# 3实例负载均衡 + 健康检查 + 限流 + HTTPS
# ============================================================

# ⑧ 修复：upstream 加 max_fails 和 fail_timeout
# max_fails=3   → 3次请求失败，标记该实例为不可用
# fail_timeout=30s → 30秒内不往这台发，30秒后重试
upstream payment_backend {
    # 轮询策略（默认）：每个实例依次各接1个请求
    server 192.168.1.11:8081 max_fails=3 fail_timeout=30s weight=1;
    server 192.168.1.12:8082 max_fails=3 fail_timeout=30s weight=1;
    server 192.168.1.13:8083 max_fails=3 fail_timeout=30s weight=1;

    # 连接保持（减少TCP握手开销）
    keepalive 32;
}

# ========== HTTP → HTTPS 跳转 ==========
server {
    listen 80;
    server_name payment.yourdomain.com;
    return 301 https://$server_name$request_uri;
}

# ========== 主配置（HTTPS） ==========
server {
    listen 443 ssl http2;
    server_name payment.yourdomain.com;

    # SSL 证书（Let's Encrypt 或购买）
    ssl_certificate     /etc/nginx/ssl/payment.crt;
    ssl_certificate_key /etc/nginx/ssl/payment.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # ========== Nginx 层限流（⑩ 第一道防线）==========
    # 每个客户端IP，支付接口每秒最多5次
    limit_req_zone $binary_remote_addr zone=payment_zone:10m rate=5r/s;
    # 回调接口：银行IP每秒最多20次（银行可能重试）
    limit_req_zone $binary_remote_addr zone=callback_zone:10m rate=20r/s;

    # ========== 支付 API ==========
    location /api/payment/initiate {
        limit_req zone=payment_zone burst=10 nodelay;
        limit_req_status 429;  # 被限流返回 429，前端友好显示

        proxy_pass         http://payment_backend;
        proxy_http_version 1.1;
        proxy_set_header   Connection "";
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;

        # 超时设置（银行API最长10秒，这里设12秒留余量）
        proxy_connect_timeout  5s;
        proxy_send_timeout     12s;
        proxy_read_timeout     12s;
    }

    # ========== 银行回调接口（不限流，返回200防止重发）==========
    location /api/payment/callback/ {
        limit_req zone=callback_zone burst=50 nodelay;

        proxy_pass         http://payment_backend;
        proxy_http_version 1.1;
        proxy_set_header   Connection "";
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;

        # 回调处理异步，超时可以短一点
        proxy_connect_timeout  3s;
        proxy_send_timeout     5s;
        proxy_read_timeout     5s;
    }

    # ========== 通用 API ==========
    location /api/ {
        proxy_pass         http://payment_backend;
        proxy_http_version 1.1;
        proxy_set_header   Connection "";
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;

        proxy_connect_timeout  5s;
        proxy_send_timeout     30s;
        proxy_read_timeout     30s;
    }

    # ========== 健康检查接口（⑧ 修复）==========
    # Spring Boot Actuator 提供 /actuator/health
    location /actuator/health {
        proxy_pass http://payment_backend;
        access_log off;  # 健康检查不记日志（太频繁）
    }

    # 屏蔽其他 actuator 接口（安全）
    location /actuator/ {
        return 403;
    }

    # ========== 日志 ==========
    access_log /var/log/nginx/payment_access.log combined;
    error_log  /var/log/nginx/payment_error.log warn;
}
```

---

## 健康检查工作原理（⑧ 修复说明）

### 被动健康检查（上面配置已包含）

```
max_fails=3 fail_timeout=30s 的含义：

正常状态：
  Nginx → 实例A → 响应200 ✅
  Nginx → 实例B → 响应200 ✅
  Nginx → 实例C → 响应200 ✅

实例B 宕机：
  Nginx → 实例B → 连接失败(fail 1)
  Nginx → 实例B → 连接失败(fail 2)
  Nginx → 实例B → 连接失败(fail 3) ← 达到 max_fails=3
  → 实例B 标记为"不可用"，30秒内所有请求分发给A和C

30秒后：
  Nginx → 实例B → 尝试1次
  → 如果成功：恢复正常轮询
  → 如果还失败：再等30秒
```

### 主动健康检查（Nginx Plus 或 OpenResty，可选）

```nginx
# Nginx Plus 版本（商业版）支持主动健康检查
upstream payment_backend {
    server 192.168.1.11:8081;
    server 192.168.1.12:8082;
    server 192.168.1.13:8083;

    health_check interval=5s    # 每5秒主动探测一次
                 fails=2        # 连续2次失败才标记下线
                 passes=3       # 连续3次成功才标记上线
                 uri=/actuator/health;  # 探测这个接口
}
```

---

## 三台服务器启动命令（完整版）

```bash
# ===== 实例A（192.168.1.11）=====
java -jar payment-system-1.0.0.jar \
  -DSERVER_PORT=8081 \
  -DDB_HOST=192.168.1.100 \        # 共享 Oracle 服务器 IP
  -DDB_PORT=1521 \
  -DDB_SID=PAYDB \
  -DDB_USERNAME=payment_user \
  -DDB_PASSWORD=your_password \
  -DREDIS_HOST=192.168.1.101 \     # 共享 Redis 服务器 IP
  -DREDIS_PORT=6379 \
  -DREDIS_PASSWORD=your_redis_pwd \
  -DSNOWFLAKE_WORKER_ID=1 \        # ② 修复：每实例不同！
  -DSNOWFLAKE_DC_ID=1 \
  -DINSTANCE_ID=inst-A \           # ⑨ 修复：日志中标识实例
  -DLOG_DIR=/data/logs/inst-A \    # 日志目录分开
  -DPAYMENT_RATE_QPS=100 \         # ⑩ 每实例100 QPS（共300）
  -DCALLBACK_RATE_QPS=300

# ===== 实例B（192.168.1.12）=====
java -jar payment-system-1.0.0.jar \
  -DSERVER_PORT=8082 \
  -DDB_HOST=192.168.1.100 \
  ...（同上，只修改以下几项）
  -DSNOWFLAKE_WORKER_ID=2 \        # ② 不同！
  -DINSTANCE_ID=inst-B \
  -DLOG_DIR=/data/logs/inst-B

# ===== 实例C（192.168.1.13）=====
java -jar payment-system-1.0.0.jar \
  -DSERVER_PORT=8083 \
  -DDB_HOST=192.168.1.100 \
  ...
  -DSNOWFLAKE_WORKER_ID=3 \        # ② 不同！
  -DINSTANCE_ID=inst-C \
  -DLOG_DIR=/data/logs/inst-C
```

---

## 部署网络拓扑图

```
外部用户
    │  HTTPS 443
    ▼
┌─────────────────────────────┐
│   Nginx（192.168.1.10）      │
│   - 负载均衡                 │
│   - SSL 终结                 │
│   - 限流 5r/s 每IP           │
│   - 健康检查 max_fails=3     │
└─────────────────────────────┘
    │            │            │
    ▼            ▼            ▼
┌────────┐  ┌────────┐  ┌────────┐
│ 实例A  │  │ 实例B  │  │ 实例C  │
│ :8081  │  │ :8082  │  │ :8083  │
│worker=1│  │worker=2│  │worker=3│
└────────┘  └────────┘  └────────┘
    │            │            │
    └────────────┼────────────┘
                 │ （共享）
    ┌────────────┼────────────┐
    ▼            ▼            ▼
┌────────┐  ┌────────┐  ┌────────┐
│Oracle  │  │ Redis  │  │  ShedLock│
│(共享)  │  │(共享)  │  │  对账只  │
│:1521   │  │:6379   │  │  跑一次  │
└────────┘  └────────┘  └────────┘
```

---

## Nginx 常用运维命令

```bash
# 测试配置文件语法是否正确
nginx -t

# 重新加载配置（不停服务）
nginx -s reload

# 查看当前连接数和状态
nginx -s status

# 查看哪个实例接了多少请求（日志分析）
awk '{print $7}' /var/log/nginx/payment_access.log | sort | uniq -c | sort -rn | head -20

# 实时查看访问日志
tail -f /var/log/nginx/payment_access.log

# 查看错误日志
tail -f /var/log/nginx/payment_error.log

# 查看每个upstream实例的状态（需要 nginx_status 模块）
curl http://localhost/nginx_status
```

---

## 修复前 vs 修复后对比

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| 实例B宕机 | Nginx继续往B转，33%请求失败 | 3次失败后自动剔除B，100%成功 |
| 促销洪峰 | 直接打穿后端，DB崩溃 | Nginx层限流 5r/s/IP，后端平稳 |
| 银行回调 | 无限制，多实例全处理 | ShedLock保证单实例处理 |
| HTTPS | 需要每个实例各自配置 | Nginx统一终结SSL，后端HTTP |
| 银行API超时 | Tomcat线程卡120秒，雪崩 | 熔断器10秒超时，自动降级 |

---

*文档版本：v2.1*  
*对应代码版本：PaymentSys 2.1（含 ShedLock、Resilience4j、Guava 限流）*

