# 旺生活 O2O 供应链中台 — Docker 与项目启动手把手教程

> 写给自己：Docker 是怎么弄的、现在跑到哪一步、下一步怎么做、常用命令全在这里。

---

## 目录

1. [Docker 是什么，为什么要用它](#1-docker-是什么为什么要用它)
2. [项目架构总览：三层结构](#2-项目架构总览三层结构)
3. [Docker Desktop 是怎么装好的](#3-docker-desktop-是怎么装好的)
4. [docker-compose.yml 逐行解读](#4-docker-composeyml-逐行解读)
5. [Docker 常用命令手册](#5-docker-常用命令手册)
6. [当前项目进度（你在第几步）](#6-当前项目进度你在第几步)
7. [第一次启动：完整操作步骤](#7-第一次启动完整操作步骤)
8. [快速执行第一个 API 验证整个链路](#8-快速执行第一个-api-验证整个链路)
9. [超卖演示：100 并发只有 10 件库存](#9-超卖演示100-并发只有-10-件库存)
10. [日常开发工作流](#10-日常开发工作流)
11. [Docker 图形界面操作指南（Docker Desktop）](#11-docker-图形界面操作指南docker-desktop)
12. [常见问题 FAQ](#12-常见问题-faq)
13. [报错了？如何查看日志定位问题](#13-报错了如何查看日志定位问题)
14. [Bug 修复历史记录](#14-bug-修复历史记录)
15. [版本升级记录](#15-版本升级记录)
16. [重要注意事项](#16-重要注意事项)
17. [未完成的功能（TODO）](#17-未完成的功能todo)

---

## 1. Docker 是什么，为什么要用它

### 不用 Docker 的痛苦

没有 Docker 之前，本地开发要：
1. 手动装 MySQL 8.0、配置字符集、建库建表
2. 手动装 Redis、改配置文件
3. 手动装 Kafka、Zookeeper、改端口……
4. 换台电脑重来一遍，还容易版本不一样出问题

### Docker 是什么（一句话）

> Docker 就像一个**集装箱**：把软件（MySQL、Redis、Kafka）打包进箱子，  
> 哪台机器都能跑，版本一样，配置一样，一条命令启动。

### 关键概念

| 概念 | 类比 | 说明 |
|------|------|------|
| **Image（镜像）** | 软件安装包 | mysql:8.0.33 就是一个镜像 |
| **Container（容器）** | 运行中的程序 | 从镜像启动后叫容器 |
| **Volume（数据卷）** | 外挂硬盘 | 容器删了，数据还在 |
| **docker-compose.yml** | 剧本/配置文件 | 写好所有服务怎么启动 |
| **Network（网络）** | 局域网 | 容器互相访问用服务名（kafka、mysql）而不是 IP |

### 本项目用 Docker 跑什么

```
本机 (localhost)
├── Spring Boot 应用 (端口 8091)  ← 你在 IntelliJ 直接跑
│
└── Docker 容器们:
    ├── sc-mysql   (端口 3307→容器内3306)  MySQL 8.0
    ├── sc-redis   (端口 6380→容器内6379)  Redis 7
    ├── sc-kafka   (端口 9092→容器内9092)  Kafka 3.6
    └── sc-kafka-ui (端口 8080)            Kafka 网页管理界面
```

**为什么 MySQL 用本机 3306，Redis 用 6380？**  
因为你本机已经装了 MySQL（3306）和 Redis（6379），Docker 服务改端口避免冲突：
- Spring Boot 连**本机 MySQL 3306**（本机已有）
- Spring Boot 连 **Docker Redis 6380**（本机 Redis 没启动）
- Spring Boot 连 **Docker Kafka 9092**（本机没装 Kafka）

---

## 2. 项目架构总览：三层结构

```
┌──────────────────────────────────────────────────────────────┐
│  凤凰会小程序 / Postman / JMeter (前端/测试)                 │
└────────────────────────┬─────────────────────────────────────┘
                         │ HTTP :8091
┌────────────────────────▼─────────────────────────────────────┐
│  Spring Boot 旺生活 O2O 供应链中台                           │
│                                                              │
│  Controller → Service → Mapper                              │
│       ↓           ↓         ↓                               │
│  Redis(防超卖)  Kafka(异步)  MySQL(持久化)                  │
└──────────┬──────────┬──────────┬───────────────────────────-┘
           │          │          │
    ┌──────▼──┐ ┌─────▼──┐ ┌────▼────┐
    │sc-redis │ │sc-kafka│ │本机MySQL│
    │:6380    │ │:9092   │ │:3306    │
    └─────────┘ └────────┘ └─────────┘
```

---

## 3. Docker Desktop 是怎么装好的

### 你现在的状态（截图确认）

从 Docker Desktop 截图可以看到 **5 个容器全部绿色运行中**：

| 容器名 | 镜像 | 端口映射 | 状态 |
|--------|------|---------|------|
| supplychain | (父组) | - | 分组 |
| sc-mysql | mysql:8.0.33 | 3307:3306 | ✅ 运行 |
| sc-redis | redis:7-alpine | 6380:6379 | ✅ 运行 |
| sc-kafka | confluentinc/cp-kafka:7.6.1 | 9092:9092 | ✅ 运行 |
| sc-kafka-ui | provectuslabs/kafka-ui:latest | 8080:8080 | ✅ 运行 |

查看主体容器：
   ```powershell
C:\Users\jczhang>docker-compose ls
NAME                STATUS              CONFIG FILES
supplychain         running(5)          C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\docker-compose.yml
   ```

### 这些容器是怎么启动的

1. 项目里有一个 `docker-compose.yml` 文件（在 `SupplyChain/` 目录）
2. 在终端执行了命令：
   ```powershell
   cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
   docker-compose --progress plain up -d mysql redis kafka kafka-ui
   ```
3. Docker 自动从 DockerHub 下载镜像（第一次慢，以后不用下载）
4. 启动容器，挂载数据卷，建立内部网络

### init.sql 是怎么跑的

`docker-compose.yml` 里配置了：
```yaml
mysql:
  volumes:
    - ./src/main/resources/db/init.sql:/docker-entrypoint-initdb.d/01_init.sql
```
MySQL 容器启动时会**自动执行** `/docker-entrypoint-initdb.d/` 目录下的 SQL 文件，  
所以 16 张表是全自动建好的，不需要手动跑 SQL。

---

## 4. docker-compose.yml 逐行解读

文件位置：`SupplyChain/docker-compose.yml`

```yaml
version: "3.9"   # ← compose 文件格式版本，3.9 是较新的稳定版

services:         # ← 所有服务都写在这里

  # ═══════════════════════════════════════
  # MySQL 服务
  # ═══════════════════════════════════════
  mysql:
    image: mysql:8.0.33          # ← 用哪个镜像（格式: 名字:版本号）
    container_name: sc-mysql     # ← 容器名字，方便 docker exec 操作
    ports:
      - "3307:3306"              # ← "宿主机端口:容器内端口"
                                 #   你从外部访问 localhost:3307
                                 #   容器内部其实是 3306
    environment:                 # ← 环境变量（相当于配置）
      MYSQL_ROOT_PASSWORD: 123456   # ← root 密码
      MYSQL_DATABASE: sc_supply_chain  # ← 自动创建这个数据库
    volumes:
      - mysql_data:/var/lib/mysql  # ← 数据持久化：容器删了数据还在
      - ./src/main/resources/db/init.sql:/docker-entrypoint-initdb.d/01_init.sql
                                   # ← 容器启动时自动执行 init.sql
    healthcheck:                 # ← 健康检查：多久检查一次、多少次失败算不健康
      test: ["CMD", "mysqladmin", "ping", ...]
      interval: 10s
      retries: 10
    networks:
      - sc-net                   # ← 加入这个虚拟网络

  # ═══════════════════════════════════════
  # Redis 服务
  # ═══════════════════════════════════════
  redis:
    image: redis:7-alpine        # ← alpine = 精简版，体积小
    ports:
      - "6380:6379"              # ← 宿主机 6380 → 容器内 6379
    command: >                   # ← 覆盖默认启动命令，加上配置
      redis-server
      --save 60 1                # ← 60秒内有1次写操作就保存到磁盘
      --maxmemory 256mb          # ← 最多用 256MB 内存
      --maxmemory-policy allkeys-lru  # ← 内存满了按LRU淘汰

  # ═══════════════════════════════════════
  # Kafka 服务 (KRaft 模式，不需要 ZooKeeper)
  # ═══════════════════════════════════════
  kafka:
    image: confluentinc/cp-kafka:7.6.1
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller  # ← KRaft: broker+controller 合一
      KAFKA_LISTENERS: CONTROLLER://kafka:29093,INTERNAL://kafka:29092,EXTERNAL://0.0.0.0:9092
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092
      # ↑ INTERNAL: 容器间互相访问用 kafka:29092
      # ↑ EXTERNAL: 你本机访问用 localhost:9092
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"  # ← 自动创建 Topic（Spring Boot 启动时建）
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk       # ← KRaft 模式必须指定集群ID

  # ═══════════════════════════════════════
  # Kafka UI (网页版管理界面)
  # ═══════════════════════════════════════
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "8080:8080"
    environment:
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092  # ← 通过内部网络连Kafka

volumes:           # ← 声明数据卷（自动创建）
  mysql_data:
  redis_data:
  kafka_data:

networks:          # ← 声明网络（自动创建，容器间用服务名互相访问）
  sc-net:
    driver: bridge
```

---

## 5. Docker 常用命令手册

### 5.1 基础操作

```powershell
# ─── 查看 ──────────────────────────────────────────────────
# 查看运行中的容器
docker ps

# 查看所有容器（包括已停止的）
docker ps -a

# 查看本地镜像
docker images

# 查看数据卷
docker volume ls

# ─── 启动/停止 ──────────────────────────────────────────────
# 启动所有服务（后台运行 -d = detach）
docker-compose --progress plain up -d

# 只启动某些服务
docker-compose --progress plain up -d mysql redis kafka kafka-ui

# 停止所有服务（容器停止，数据不丢）
docker-compose stop

# 停止并删除容器（数据卷保留，下次 up 数据还在）
docker-compose down

# 停止并删除容器+数据卷（慎用！数据清空）
docker-compose down -v

# ─── 日志 ──────────────────────────────────────────────────
# 查看某个容器日志
docker logs sc-mysql
docker logs sc-kafka

# 实时跟踪日志（Ctrl+C 退出）
docker logs -f sc-kafka

# 最后 50 行日志
docker logs --tail 50 sc-redis

# ─── 进入容器 ──────────────────────────────────────────────
# 进入 MySQL 容器执行命令
docker exec -it sc-mysql mysql -uroot -p123456

# 进入 Redis 容器，连接 CLI（database 1 是我们用的）
docker exec -it sc-redis redis-cli -n 1

# 进入 Kafka 容器
docker exec -it sc-kafka bash
```

### 5.2 docker-compose 操作

```powershell
# 注意：docker-compose 命令必须在有 docker-compose.yml 的目录执行
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain

# 查看所有服务状态
docker-compose ps

# 查看某服务日志
docker-compose logs -f kafka

# 重启某个服务
docker-compose restart redis

# 重建某个服务（配置改了）
docker-compose up -d --force-recreate kafka
```

### 5.3 Redis 操作（用于调试库存）

```powershell
# 进入 Redis CLI（database 1）
docker exec -it sc-redis redis-cli -n 1

# 以下命令在 redis-cli 里执行：
GET sc:inventory:available:1:1234567890124   # 查看某SKU的Redis库存
SET sc:inventory:available:1:1234567890124 10  # 手动设置库存（模拟超卖前）
KEYS sc:inventory:*                          # 查看所有库存Key
FLUSHDB                                      # 清空 database 1（慎用）
```

### 5.4 MySQL 操作

```powershell
# 进入本机 MySQL（3306）
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p123456 sc_supply_chain

# 或进入 Docker MySQL（3307）
docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain
```

---

## 6. 当前项目进度（你在第几步）

### ✅ 已完成

| # | 内容 | 状态 |
|---|------|------|
| 1 | 项目代码全部生成（74个Java文件）| ✅ 完成 |
| 2 | Bug修复（Redis序列化/lockStock-2/JDBC编码/Redisson） | ✅ 完成 |
| 3 | `docker-compose.yml` 创建 | ✅ 完成 |
| 4 | Docker 服务启动（MySQL/Redis/Kafka/Kafka-UI） | ✅ **运行中** |
| 5 | 本机 MySQL `sc_supply_chain` 库建好（16张表）| ✅ 完成 |
| 6 | Docker MySQL 也初始化好了（init.sql 自动执行）| ✅ 完成 |
| 7 | Spring Boot 代码编译通过（BUILD SUCCESS）| ✅ 完成 |

### ⏳ 下一步（你要做的）

| # | 内容 | 怎么做 |
|---|------|-------|
| 8 | **启动 Spring Boot 应用** | 见第7节 |
| 9 | **执行 API 测试** | 见第8节 |
| 10 | **超卖并发模拟** | 见第9节 |

---

## 7. 第一次启动：完整操作步骤

### 步骤 1：确认 Docker 容器正在运行

打开 Docker Desktop → Containers，确认 sc-mysql/sc-redis/sc-kafka/sc-kafka-ui 都是绿色。

或用命令：
```powershell
docker ps
# 应该看到4个容器 STATUS 都是 Up ... (healthy)
```

### 步骤 2：启动 Spring Boot（方式一：IntelliJ IDEA — 推荐）

1. 打开 `SupplyChainApplication.java`
2. 点击 `main` 方法旁边的绿色 ▶ 按钮
3. 但要先设置 **Run Configuration** 的环境变量：
   - 点击右上角运行配置下拉 → Edit Configurations
   - 找到 `SupplyChainApplication`
   - 在 `Environment variables` 里填：
     ```
     DB_HOST=localhost;DB_PORT=3306;DB_USERNAME=root;DB_PASSWORD=123456;REDIS_HOST=localhost;REDIS_PORT=6380;REDIS_PASSWORD=;KAFKA_SERVERS=localhost:9092
     ```
4. 点 Apply → OK → 运行

### 步骤 2（方式二：PowerShell 命令行）

```powershell
# 设置 Maven 路径（每次新开 PowerShell 需要执行）
$mvn = "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_321"

# 设置连接配置
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"          # 本机 MySQL
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "123456"
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6380"       # Docker Redis
$env:REDIS_PASSWORD = ""
$env:KAFKA_SERVERS = "localhost:9092"  # Docker Kafka

# 进入项目目录并启动
Set-Location "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain"
& $mvn spring-boot:run
```

### 步骤 3：确认启动成功

控制台应该出现：
```
Supply Chain Started Successfully
Tomcat started on port(s): 8091
```

### 步骤 4：访问 Swagger 文档

浏览器打开：**http://localhost:8091/swagger-ui/index.html**

可以看到所有 API 接口文档，可以直接在页面上调用接口。

### 步骤 5：访问 Kafka 管理界面

浏览器打开：**http://localhost:8080**

可以看到 Kafka Topics、消息、Consumer Groups。

---

## 8. 快速执行第一个 API 验证整个链路

> 按顺序执行，体验从商品创建 → 入库 → 下单 → 支付 → 出库的全流程

### 8.1 准备测试数据（在 MySQL 里执行）

```sql
-- 方式A：本机 MySQL
-- "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p123456 sc_supply_chain

-- 方式B：Docker MySQL  
-- docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain

INSERT IGNORE INTO sc_category VALUES (1, 0, '生鲜食品', 1, 0, NOW(), NULL);
INSERT IGNORE INTO sc_category VALUES (2, 1, '乳制品',   2, 0, NOW(), NULL);
INSERT IGNORE INTO sc_warehouse VALUES (1, '碧桂园中央仓', '广州市天河区', 'MAIN', NOW());
INSERT IGNORE INTO sc_store VALUES (1, '碧桂园天悦社区店', 100, '广州市', '13800138000', 'OPEN', NOW(), 0);
```

### 8.2 第一步：创建商品 SPU

打开 http://localhost:8091/doc.html → **商品中心** → POST `/api/product/spu`

或用 curl：
```bash
curl -X POST http://localhost:8091/api/product/spu \
  -H "Content-Type: application/json" \
  -d '{"spuName":"蒙牛纯牛奶","categoryId":2,"brand":"蒙牛","freshType":"CHILLED"}'
```

**返回**：`{"code":200,"data":1234567890123}` — 记录 SPU_ID

### 8.3 第二步：创建 SKU

```bash
curl -X POST http://localhost:8091/api/product/sku \
  -H "Content-Type: application/json" \
  -d '{"spuId":1234567890123,"skuName":"蒙牛纯牛奶250ml*12盒","price":39.90,"weight":3.0}'
```

**返回**：`{"code":200,"data":1234567890124}` — 记录 SKU_ID

### 8.4 第三步：SPU 上架

```bash
curl -X PUT http://localhost:8091/api/product/spu/1234567890123/on-sale
```

### 8.5 第四步：入库 100 件（这会同时写 DB + 同步 Redis）

```bash
curl -X POST http://localhost:8091/api/inventory/inbound \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": 1234567890124,
    "warehouseId": 1,
    "qty": 100,
    "batchNo": "BATCH-20260529-001",
    "productDate": "2026-05-01",
    "expireDate": "2026-06-28"
  }'
```

### 8.6 验证入库效果

```bash
# 查看 DB 库存
curl http://localhost:8091/api/inventory/1/1234567890124

# 查看 Redis 库存（应该是 100）
curl http://localhost:8091/api/inventory/1/1234567890124/redis
```

在 Docker Desktop → sc-redis 的 CLI，或 redis-cli 里也可以验证：
```bash
docker exec -it sc-redis redis-cli -n 1 GET sc:inventory:available:1:1234567890124
# 输出: "100"
```

### 8.7 第五步：下单（核心！触发 Lua 原子扣减）

```bash
curl -X POST http://localhost:8091/api/fulfillment/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "storeId": 1,
    "skuId": 1234567890124,
    "qty": 2,
    "payAmount": 79.80,
    "address": "广州市天河区碧桂园天悦1栋101",
    "warehouseId": 1
  }'
```

**返回**：`{"code":200,"data":"ORD-XXXXXXXXXXXXXXXX"}` — 记录订单号

下单后立即验证 Redis（应该从 100 减到 98）：
```bash
docker exec -it sc-redis redis-cli -n 1 GET sc:inventory:available:1:1234567890124
# 输出: "98"
```

等 1 秒（Kafka 异步落库），再查 DB：
```bash
curl http://localhost:8091/api/inventory/1/1234567890124
# available_qty=98, locked_qty=2
```

### 8.8 第六步：支付 → 出库（FIFO）

```bash
# 支付
curl -X POST http://localhost:8091/api/fulfillment/order/ORD-你的订单号/paid

# 出库（FIFO 批次分配）
curl -X POST http://localhost:8091/api/fulfillment/order/ORD-你的订单号/outbound
```

### 8.9 全链路验证 SQL

```sql
-- 查看订单状态（应该是 OUTBOUND）
SELECT order_no, status, qty FROM sc_fulfillment_order ORDER BY create_time DESC LIMIT 5;

-- 查看库存变化
SELECT sku_id, available_qty, locked_qty, total_qty FROM sc_inventory WHERE warehouse_id=1;

-- 查看批次 FIFO 扣减（remain_qty 应该减了 2）
SELECT batch_no, inbound_qty, remain_qty, expire_date FROM sc_inventory_batch;

-- 查看操作日志
SELECT op_type, delta_qty, ref_no, create_time FROM sc_inventory_log ORDER BY create_time DESC LIMIT 10;
```

---

## 9. 超卖演示：100 并发只有 10 件库存

### 9.1 准备环境

```powershell
# 设置 Redis 库存为 10
docker exec -it sc-redis redis-cli -n 1 SET sc:inventory:available:1:SKU_ID 10

# 同步 DB
$mysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
& $mysqlBin -uroot -p123456 sc_supply_chain -e `
  "UPDATE sc_inventory SET available_qty=10, locked_qty=0, total_qty=10 WHERE warehouse_id=1;"
```

### 9.2 方式一：PowerShell 并发（Windows 无需安装额外工具）

```powershell
# 100 个并发请求同时下单（库存只有 10）
$SKU_ID = 1234567890124   # 替换为你的 SKU ID
$body = "{`"storeId`":1,`"skuId`":$SKU_ID,`"qty`":1,`"payAmount`":39.90,`"address`":`"测试地址`",`"warehouseId`":1}"

$jobs = 1..100 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($b)
        try {
            $result = Invoke-RestMethod -Method POST `
                -Uri "http://localhost:8091/api/fulfillment/order/create" `
                -ContentType "application/json" `
                -Body $b
            "SUCCESS: $($result.data)"
        } catch {
            "FAIL: $($_.Exception.Response.StatusCode)"
        }
    } -ArgumentList $body
}

Write-Host "等待 100 个并发请求完成..."
$results = $jobs | Wait-Job | Receive-Job

# 统计成功/失败
$success = ($results | Where-Object { $_ -match "SUCCESS" }).Count
$fail    = ($results | Where-Object { $_ -match "FAIL"    }).Count
Write-Host "成功: $success 笔 | 失败: $fail 笔"
# 期望结果: 成功 10 笔 | 失败 90 笔  ← 零超卖！
```

### 9.3 方式二：JMeter（图形化报告）

1. 下载 JMeter：https://jmeter.apache.org/download_jmeter.cgi
2. 运行 `bin\jmeter.bat`
3. 新建 Thread Group：100 线程，Ramp-up 1秒
4. 添加 HTTP Request：POST http://localhost:8091/api/fulfillment/order/create
5. Body: `{"storeId":1,"skuId":SKU_ID,"qty":1,"payAmount":39.90,"address":"测试","warehouseId":1}`
6. 添加 HTTP Header Manager：Content-Type: application/json
7. 添加 Aggregate Report 查看结果

### 9.4 验证超卖防护结果

```powershell
# 查看 Redis 库存（应该精确是 0）
docker exec -it sc-redis redis-cli -n 1 GET sc:inventory:available:1:SKU_ID

# 查看成功下单数（应该精确是 10）
$mysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
& $mysqlBin -uroot -p123456 sc_supply_chain -e `
  "SELECT status, COUNT(*) cnt FROM sc_fulfillment_order WHERE sku_id=SKU_ID GROUP BY status;"
```

**期望结果**：
```
status   | cnt
---------|----
PENDING  | 10   ← 精确 10 笔，零超卖 ✅
```

---

## 10. 日常开发工作流

### 每天上班启动流程

```
第一步：确认 Docker 容器（Docker Desktop → 看绿色圆点）
        如果没起来，打开 PowerShell：
        cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
        docker-compose --progress plain up -d mysql redis kafka kafka-ui

第二步：在 IntelliJ 启动 SupplyChainApplication（配好环境变量）

第三步：浏览器打开 http://localhost:8091/doc.html 验证启动
```

### 代码修改后

```
修改 Java 代码 → IntelliJ 自动热重载（或重启应用）
修改 docker-compose.yml → 执行 docker-compose up -d --force-recreate 服务名
修改 init.sql → 需要重建 MySQL 容器（见下方）
```

### 重置数据库（重新建表）

```powershell
# 停止并删除 MySQL 容器和数据卷（数据清空！）
docker-compose stop mysql
docker-compose rm -f mysql
docker volume rm supplychain_mysql_data

# 重新启动（会重新执行 init.sql）
docker-compose --progress plain up -d mysql
```

---

## 11. Docker 图形界面操作指南（Docker Desktop）

### 界面功能说明

```
Docker Desktop 左侧菜单：

┌──────────────┐
│ Containers   │ ← 容器管理（最常用）
│ Images       │ ← 镜像列表
│ Volumes      │ ← 数据卷
│ Builds       │ ← 构建历史
└──────────────┘
```

### Containers 界面操作

在你的截图里可以看到：

| 按钮 | 功能 |
|------|------|
| ▶ (蓝色)  | 启动停止的容器 |
| ⬛ (蓝色)  | 停止运行中的容器 |
| 🗑️ (红色)  | 删除容器 |
| ⋮ (三点)  | 更多操作（Logs、Exec、Inspect）|

### 查看容器日志（Docker Desktop）

点击容器名 `sc-kafka` → 顶部会出现 `Logs` 标签 → 实时查看日志

### 进入容器执行命令（Docker Desktop）

点击容器名 → 顶部 `Exec` 标签 → 选择 `/bin/bash` → 执行命令

例如进入 MySQL：
```bash
mysql -uroot -p123456 sc_supply_chain
SHOW TABLES;
```

---

## 12. 常见问题 FAQ

### Q1：Docker Desktop 没有绿色圆点，容器没起来？

```powershell
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain

# 重新启动所有服务
docker-compose --progress plain up -d mysql redis kafka kafka-ui

# 查看状态
docker-compose ps
```

### Q2：Spring Boot 启动报 "Connection refused: localhost:6380"？

Redis 容器没起来，检查：
```powershell
docker ps | Select-String "redis"
# 如果没有，重新启动：
docker-compose --progress plain up -d redis
```

### Q3：Spring Boot 报 "Could not find class: xxx"？

需要重新编译：
```powershell
$mvn = "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_321"
Set-Location "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain"
& $mvn compile
```

### Q4：Kafka Topic 没有创建？

Spring Boot 启动时会自动创建 Topics（通过 `KafkaConfig.java`）。  
如果 Kafka 先于 Spring Boot 启动就会自动创建。  
可在 http://localhost:8080 的 Kafka UI 验证 Topics 是否存在。

### Q5：下单报 "库存不足" 但已经入库了？

Redis 缓存可能没有数据，需要预热：
```bash
curl -X POST http://localhost:8091/api/inventory/1/SKU_ID/warmup
```

### Q6：想完全重置（清空所有数据重新开始）？

```powershell
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
docker-compose down -v    # 删除所有容器和数据卷
docker-compose --progress plain up -d mysql redis kafka kafka-ui
# 等 MySQL 健康检查通过，init.sql 会自动重新跑
```

---

## 附录：项目文件结构

```
SupplyChain/
├── docker-compose.yml          ← Docker 服务编排文件
├── .env                        ← 环境变量（本地/Docker两种配置说明）
├── Dockerfile                  ← 打包 Spring Boot 为 Docker 镜像（生产用）
├── pom.xml                     ← Maven 依赖
├── API测试指南.md               ← 完整 API 测试文档（curl + JMeter）
├── 供应链中台核心技术详解.md    ← 技术原理 + 面试问题
├── src/
│   └── main/
│       ├── java/com/sc/supplychain/
│       │   ├── config/          ← Redis、Kafka、Swagger 配置
│       │   ├── controller/      ← 6个 REST 控制器
│       │   ├── service/impl/    ← 核心服务实现（含 Lua 防超卖）
│       │   ├── mapper/          ← MyBatis-Plus Mapper
│       │   ├── entity/          ← 16 个实体类
│       │   ├── job/             ← 定时任务（补货/效期/对账）
│       │   ├── listener/        ← Kafka 消费者
│       │   └── mq/              ← Kafka 生产者
│       └── resources/
│           ├── application.yml  ← 应用配置
│           ├── db/init.sql      ← 数据库初始化 SQL（16张表）
│           └── lua/inventory_lock.lua  ← 防超卖 Lua 脚本
```

---

## 快速参考卡片（贴墙上）

```
╔════════════════════════════════════════════════════════════╗
║  旺生活供应链 — 每日开发快速参考                          ║
╠════════════════════════════════════════════════════════════╣
║  启动 Docker:                                             ║
║  cd SupplyChain                                           ║
║  docker-compose --progress plain up -d mysql redis kafka  ║
║  kafka-ui                                                 ║
╠════════════════════════════════════════════════════════════╣
║  启动 Spring Boot 环境变量:                               ║
║  DB_HOST=localhost;DB_PORT=3306;DB_USERNAME=root;         ║
║  DB_PASSWORD=123456;REDIS_HOST=localhost;                 ║
║  REDIS_PORT=6380;REDIS_PASSWORD=;                         ║
║  KAFKA_SERVERS=localhost:9092                             ║
╠════════════════════════════════════════════════════════════╣
║  访问地址:                                                ║
║  Swagger UI:   http://localhost:8091/swagger-ui/index.html    ║
║  Kafka UI:     http://localhost:8080                      ║
║  Druid 监控:   http://localhost:8091/druid/ (admin/admin123)║
╠════════════════════════════════════════════════════════════╣
║  查看 Redis 库存:                                         ║
║  docker exec -it sc-redis redis-cli -n 1                 ║
║  GET sc:inventory:available:1:{SKU_ID}                    ║
╚════════════════════════════════════════════════════════════╝
```

---

## 13. 报错了？如何查看日志定位问题

> **核心思路**：API 返回 500 → 不要猜 → 先看日志找 `ERROR` 或异常堆栈 → 再修复

---

### 13.1 日志来自哪里？

本项目有两类日志：

| 日志来源 | 在哪里看 | 包含什么 |
|---------|---------|---------|
| **Spring Boot 应用日志** | 终端控制台 / `app.log` 文件 | 你的 Java 代码报什么错、SQL 执行情况、Kafka 消费情况 |
| **Docker 容器日志** | `docker logs 容器名` | MySQL/Redis/Kafka 自身的错误 |

**一般 API 报 500，99% 看 Spring Boot 日志就够了。**

---

### 13.2 方式一：IntelliJ IDEA 控制台查看（最直观）

如果你在 IntelliJ 里启动的 Spring Boot：

1. 底部点击 **Run** 或 **Debug** 标签
2. 在输出框里按 **Ctrl + F**，搜索 `ERROR`
3. 找到红色的异常堆栈，例如：

```
2026-05-29 14:23:11.456 ERROR 12345 --- [nio-8091-exec-3] c.s.s.c.GlobalExceptionHandler : 系统异常
java.lang.NullPointerException: null
    at com.sc.supplychain.service.impl.ProductServiceImpl.createSku(ProductServiceImpl.java:87)
    at com.sc.supplychain.controller.ProductController.createSku(ProductController.java:45)
    ...
```

**怎么读这个堆栈：**
- 第一行 `ERROR` 后面是异常类型（`NullPointerException`）
- `at com.sc.supplychain...` 告诉你是哪个类哪一行出的问题
- **从上往下看**，找第一个 `com.sc.supplychain` 开头的行，就是你代码的问题所在

---

### 13.3 方式二：PowerShell 启动并把日志写到文件（推荐排查时用）

```powershell
# 1. 设置变量
$mvn = "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$env:JAVA_HOME   = "C:\Program Files\Java\jdk1.8.0_321"
$env:DB_HOST     = "localhost"; $env:DB_PORT     = "3306"
$env:DB_USERNAME = "root";      $env:DB_PASSWORD = "123456"
$env:REDIS_HOST  = "localhost"; $env:REDIS_PORT  = "6380"; $env:REDIS_PASSWORD = ""
$env:KAFKA_SERVERS = "localhost:9092"

# 2. 进入项目目录
Set-Location "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain"

# 3. 启动，同时把日志写入文件（Tee-Object = 既显示在屏幕又写文件）
& $mvn spring-boot:run 2>&1 | Tee-Object -FilePath "app.log"
```

启动后，**新开一个 PowerShell 窗口** 来查日志：

```powershell
# 实时跟踪日志（类似 Linux 的 tail -f）
Get-Content "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\app.log" -Wait

# 只看 ERROR 行
Get-Content "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\app.log" | Select-String "ERROR"

# 找最近 50 行（包含错误上下文）
Get-Content "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\app.log" -Tail 50
```

---

### 13.4 方式三：开启 SQL 日志（排查数据库问题）

在 `application.yml` 里 MyBatis 有这个配置：

```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl   # ← 开启SQL日志
```

开启后，执行 API 时控制台会打印：

```
==>  Preparing: SELECT * FROM sc_sku WHERE id = ? AND deleted = 0
==> Parameters: 2060279181856923649(Long)
<==      Total: 0          ← ← ← 这里 0 行，说明查不到数据！
```

这对排查"明明有数据但查不到"、"SQL 参数传错"非常有用。

> ⚠️ 生产环境记得关掉，太多日志影响性能。

---

### 13.5 方式四：Docker 容器日志

如果 API 报错但 Spring Boot 日志没有异常，可能是中间件问题：

```powershell

# ─── 主体 容器日志 ──────────────────────────────────────────
# 看最后 100 行
docker logs sc-app 2>&1 | Select-Object -First 50

# ─── MySQL 容器日志 ──────────────────────────────────────────
# 看最后 100 行
docker logs --tail 100 sc-mysql

# 实时跟踪（Ctrl+C 退出）
docker logs -f sc-mysql

# ─── Redis 容器日志 ──────────────────────────────────────────
docker logs --tail 50 sc-redis

# ─── Kafka 容器日志 ──────────────────────────────────────────
docker logs --tail 100 sc-kafka

# ─── 所有容器一起看（用 docker-compose）────────────────────
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
docker-compose logs --tail 50        # 所有服务最后50行
docker-compose logs -f kafka         # 实时跟踪 Kafka
```

---

### 13.6 快速排查流程图

```
收到 HTTP 500
      │
      ▼
查看 Spring Boot 日志
      │
      ├─── 有 ERROR/Exception ──→ 看异常类型和第一个 com.sc... 行
      │         │
      │         ├── NullPointerException → 某个对象是 null，检查数据是否存在
      │         ├── DataIntegrityViolation → SQL 约束违反（唯一键重复等）
      │         ├── BadSqlGrammarException → SQL 语法错误（检查字段名）
      │         ├── ConnectException → 连不上 MySQL/Redis/Kafka
      │         └── ClassCastException → 类型转换错误
      │
      └─── 没有 ERROR ──→ 看 GlobalExceptionHandler 有没有捕获到
                │
                └──→ 开启 SQL 日志 + 看 Docker 容器日志
```

---

### 13.7 实战案例：排查 POST /api/product/sku 返回 500

**第一步：复现并同时看日志**

```powershell
# 窗口1：确保 app.log 正在记录
Get-Content "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\app.log" -Wait

# 窗口2：发出导致500的请求
Invoke-RestMethod -Method POST `
  -Uri "http://localhost:8091/api/product/sku" `
  -ContentType "application/json" `
  -Body '{"spuId":2060279181856923649,"skuName":"蒙牛纯牛奶250ml*12盒","price":39.90,"weight":3.0}'
```

**第二步：在日志里找 ERROR**

在 app.log 里搜索 `ERROR`，重点看：

```powershell
Select-String "ERROR|Exception|cause" `
  "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\app.log"
```

**第三步：根据异常类型定位**

| 看到什么 | 说明 | 怎么查 |
|---------|------|-------|
| `No enum constant` | 枚举值不对 | 检查请求参数的枚举字段 |
| `Table 'xxx' doesn't exist` | 表不存在 | 重新执行 init.sql |
| `Field 'xxx' doesn't have a default value` | 必填字段没传 | 检查实体类或 SQL |
| `Duplicate entry` | 唯一键冲突 | 数据已存在，换值再试 |
| `SPU不存在` (业务异常) | spuId 对应的 SPU 查不到 | 先建 SPU，用返回的 spuId |
| `NullPointerException` | 空指针 | 看堆栈第一个 `com.sc` 行 |

**第四步：常见 500 根因 — SPU ID 不存在**

`POST /api/product/sku` 的 `spuId` 必须是数据库里真实存在的 SPU。

```powershell
# 先创建 SPU，拿到真实 ID
$spu = Invoke-RestMethod -Method POST `
  -Uri "http://localhost:8091/api/product/spu" `
  -ContentType "application/json" `
  -Body '{"spuName":"蒙牛纯牛奶","categoryId":2,"brand":"蒙牛","freshType":"CHILLED"}'

$spuId = $spu.data
Write-Host "SPU ID = $spuId"

# 用这个真实的 spuId 创建 SKU
$sku = Invoke-RestMethod -Method POST `
  -Uri "http://localhost:8091/api/product/sku" `
  -ContentType "application/json" `
  -Body "{`"spuId`":$spuId,`"skuName`":`"蒙牛纯牛奶250ml*12盒`",`"price`":39.90,`"weight`":3.0}"

Write-Host "SKU ID = $($sku.data)"
```

---

### 13.8 配置日志级别（让日志更详细或更简洁）

在 `application.yml` 里可以调整日志级别：

```yaml
logging:
  level:
    root: INFO                          # 全局级别
    com.sc.supplychain: DEBUG           # 你的代码：DEBUG 最详细
    org.springframework.kafka: WARN     # Kafka 框架：只看警告
    org.mybatis: DEBUG                  # MyBatis SQL：DEBUG 看SQL
  file:
    name: app.log                       # ← 加上这行，自动把日志写到文件
    max-size: 50MB                      # 单文件最大 50MB
    max-history: 7                      # 保留 7 天
```

加上 `logging.file.name: app.log` 后，无需 `Tee-Object`，Spring Boot 自动写文件。

> 日志级别从低到高：`TRACE < DEBUG < INFO < WARN < ERROR`  
> 设置 DEBUG 会看到所有内容；设置 ERROR 只看错误。

---

### 13.9 一键诊断脚本

复制到 PowerShell 执行，自动检查所有关键日志：

```powershell
Write-Host "=== 检查 Docker 容器状态 ===" -ForegroundColor Cyan
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

Write-Host "`n=== MySQL 最近错误 ===" -ForegroundColor Cyan
docker logs --tail 20 sc-mysql 2>&1 | Select-String "ERROR|error|Error"

Write-Host "`n=== Redis 最近错误 ===" -ForegroundColor Cyan
docker logs --tail 20 sc-redis 2>&1 | Select-String "ERROR|error|Error"

Write-Host "`n=== Kafka 最近错误 ===" -ForegroundColor Cyan
docker logs --tail 20 sc-kafka 2>&1 | Select-String "ERROR|error|Error"

$logFile = "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\app.log"
if (Test-Path $logFile) {
    Write-Host "`n=== Spring Boot 最近 ERROR ===" -ForegroundColor Cyan
    Get-Content $logFile | Select-String "ERROR" | Select-Object -Last 20
} else {
    Write-Host "`n[提示] app.log 不存在，请用 Tee-Object 启动或配置 logging.file.name" -ForegroundColor Yellow
}
```

---

*文档版本: v1.2 | 2026-06-01 | 碧桂园旺生活 O2O 供应链中台*

---

## 14. Bug 修复历史记录

> 按时间顺序记录所有发现的 Bug 和修复方式，方便回溯和面试时讲清楚排查思路。

---

### Bug #1 ⚠️ Redis Lua 脚本 `tonumber()` 解析失败

**现象**：入库成功、下单接口报"库存不足"，Redis 里明明有库存。

**根本原因**：  
使用 `RedisTemplate<String, Object>` 配合 Jackson 序列化，往 Redis 写数值时会变成：
```
["java.lang.Long", 100]
```
Lua 脚本里 `tonumber(redis.call('GET', key))` 解析这个字符串返回 `nil`，扣减逻辑直接走了"库存不足"分支。

**修复**：把所有数值型 Redis 操作全部改用 `StringRedisTemplate`，存的是纯字符串 `"100"`，Lua 能正常解析。

```java
// 修复前（错误）
redisTemplate.opsForValue().set(key, qty);  // 存入 ["java.lang.Long",100]

// 修复后（正确）
stringRedisTemplate.opsForValue().set(key, String.valueOf(qty));  // 存入 "100"
```

**影响文件**：`InventoryServiceImpl.java`、`RedisConfig.java`

---

### Bug #2 ⚠️ 缓存穿透导致下单失败（-2 返回值处理错误）

**现象**：Redis 缓存没有预热时，第一次下单直接报"库存不足"，即使 DB 里有库存。

**根本原因**：  
Lua 脚本约定返回 `-2` 表示"Key 不存在（缓存未预热）"，但代码里把 `-2` 和 `-1`（真正库存不足）一起处理，没有触发缓存预热重试。

**修复**：显式判断 `-2`，触发从 DB 预热 Redis，然后重试一次 Lua 扣减。

```java
Long result = stringRedisTemplate.execute(inventoryLockScript, keys, args);
if (result == null || result == -2L) {
    warmupRedisStock(warehouseId, skuId);  // 从DB读取写入Redis
    result = stringRedisTemplate.execute(inventoryLockScript, keys, args); // 重试
}
if (result == null || result < 0) throw SupplyChainException.stockInsufficient(skuId);
```

**影响文件**：`InventoryServiceImpl.java`、`inventory_lock.lua`

---

### Bug #3 ⚠️ 履约订单缺少仓库ID字段，取消/出库逻辑用错仓库

**现象**：调用取消订单或出库接口，找不到对应仓库的库存记录，报空指针或操作错仓。

**根本原因**：  
`FulfillmentOrder` 实体类没有 `warehouseId` 字段，取消/出库时代码用的是硬编码的 `DEFAULT_WAREHOUSE_ID=1`，多仓场景直接出错。

**修复**：
1. `FulfillmentOrder` 实体加 `private Long warehouseId;`
2. `init.sql` 的 `sc_fulfillment_order` 表加 `warehouse_id` 列
3. 下单时保存 `warehouseId`，取消/出库时用 `order.getWarehouseId()`

**影响文件**：`FulfillmentOrder.java`、`FulfillmentServiceImpl.java`、`db/init.sql`

---

### Bug #4 ⚠️ 效期预警自动填充时间为 null

**现象**：效期预警记录保存后，`warn_time` 字段为 null，导致预警时间查询出错。

**根本原因**：  
`MetaObjectFillHandler`（MyBatis-Plus 自动填充）只填充了 `createTime`/`updateTime`，漏掉了 `warnTime`。

**修复**：在 `insertFill()` 里补充 `warnTime` 的填充逻辑。

```java
@Override
public void insertFill(MetaObject metaObject) {
    this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
    this.strictInsertFill(metaObject, "warnTime", LocalDateTime.class, LocalDateTime.now()); // 补充
}
```

**影响文件**：`MetaObjectFillHandler.java`

---

### Bug #5 ⚠️ JDBC URL 编码参数错误，连接 MySQL 报错

**现象**：Spring Boot 启动时报 `Unsupported character encoding 'utf8mb4'`，无法连接数据库。

**根本原因**：  
`application.yml` 里写了 `characterEncoding=utf8mb4`，这是 MySQL 的字符集名称，不是 Java 的字符集名称，JDBC 驱动不认识。

**修复**：改为 Java 标准字符集名称 `UTF-8`。

```yaml
# 修复前
url: ...?characterEncoding=utf8mb4&...

# 修复后
url: ...?characterEncoding=UTF-8&...
```

**影响文件**：`application.yml`

---

### Bug #6 ⚠️ Redisson 无密码 Redis 报 AUTH 错误

**现象**：Spring Boot 启动时报 `ERR AUTH <password> called without any password configured`，应用无法启动。

**根本原因**：  
`pom.xml` 引入了 `redisson-spring-boot-starter`，它在 Spring Boot 启动时自动连接 Redis 并发送 `AUTH ""` 命令，但本地 Redis 没有设置密码，拒绝了这个命令。

**修复**：注释掉 `redisson-spring-boot-starter` 依赖（项目代码实际上没有用到 Redisson，是预留的 Phase 2 依赖）。

```xml
<!-- 暂未启用，先注释避免无密码Redis的AUTH问题 -->
<!--
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.17.7</version>
</dependency>
-->
```

**影响文件**：`pom.xml`

---

### Bug #7 ⚠️ Springfox 3.0.0 + Spring Boot 2.6.x NPE 崩溃

**现象**：Spring Boot 启动时报 `NullPointerException at WebMvcPatternsRequestConditionWrapper.getPatterns:56`，应用崩溃。

**根本原因**：  
Spring Boot 2.6.x 默认路径匹配策略改成了 `PathPatternParser`，而 Springfox 3.0.0 / Knife4j 3.x 内部写死使用 `AntPathMatcher`，两者不兼容。

**修复（最终方案）**：放弃 Springfox，改用 SpringDoc OpenAPI（`springdoc-openapi-ui:1.7.0`），后续再升级到 Knife4j 4.x。

**影响文件**：`pom.xml`、`SwaggerConfig.java`

---

### Bug #8 ⚠️ application.yml 有重复的 `logging:` key，YAML 解析崩溃

**现象**：Spring Boot（Docker 容器）启动时报：
```
DuplicateKeyException: found duplicate key logging in 'reader', line 131, column 1
```

**根本原因**：  
在修改日志配置时，新增了一个 `logging:` 块，但原文件已经有一个 `logging:` 块了，YAML 不允许同层重复 key。

**修复**：将两个 `logging:` 块合并为一个，保留全部配置项。

**影响文件**：`application.yml`

---

### Bug #9 ⚠️ Docker 容器内 DB_PORT 默认值错误导致连接失败

**现象**：Spring Boot 在 Docker 容器内启动时，连接 MySQL 报 `Connection refused: mysql:3307`。

**根本原因**：  
`application.yml` 里 `DB_PORT` 的默认值写的是 `3307`（宿主机映射端口），但容器内连接的是 Docker 网络内的 `mysql` 服务，其端口是 `3306`（容器内部端口）。

**修复**：把默认值改为 `3306`，宿主机本地启动时通过环境变量 `DB_PORT=3306`（本机MySQL）传入。

```yaml
# 修复前
url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3307}/...

# 修复后
url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/...
```

**影响文件**：`application.yml`

---

## 15. 版本升级记录

### v1.0 → v1.1（2026-05-29）

#### 🔄 Swagger / API 文档框架替换

| 项目 | 旧版本 | 新版本 | 原因 |
|------|--------|--------|------|
| API 文档框架 | Knife4j 3.0.3（基于 Springfox）| Knife4j 4.4.0（基于 SpringDoc）| Spring Boot 2.6.x NPE 兼容问题 |
| 依赖包 | `knife4j-spring-boot-starter:3.0.3` | `knife4j-openapi3-spring-boot-starter:4.4.0` | 同上 |
| Swagger 注解 | `@Api` / `@ApiOperation`（Swagger 2）| `@Tag` / `@Operation`（OpenAPI 3）| Knife4j 4.x 不再支持旧注解 |

**升级后访问地址变化**：

| | 旧地址 | 新地址 |
|-|--------|--------|
| 推荐 UI | `/doc.html`（Knife4j 风格）| `/doc.html` ✅（Knife4j 4.x 保留）|
| Swagger UI | `/swagger-ui.html` | `/swagger-ui/index.html` |

#### 🔄 Docker 架构升级

| 项目 | 旧方式 | 新方式 | 原因 |
|------|--------|--------|------|
| Spring Boot 运行 | 本地 `mvn spring-boot:run` | Docker 容器 `sc-app` | 全部中间件+应用都在 Docker，环境统一 |
| Dockerfile | 多阶段构建（Docker 内跑 Maven）| 单阶段构建（复制本地 JAR）| 多阶段首次构建要下载 Maven 依赖，慢10分钟 |
| `supply-chain-app` 服务 | 需要 `--profile app` 才启动 | 默认随 `docker-compose up` 启动 | 简化操作 |

#### 🔄 日志配置升级

| 项目 | 旧配置 | 新配置 |
|------|--------|--------|
| 日志文件 | 无文件输出 | 自动写入 `app.log` |
| 业务代码级别 | `INFO` | `DEBUG`（方便排查问题）|
| Kafka 框架日志 | `INFO` | `WARN`（减少噪音）|

---

## 16. 重要注意事项

> 踩过坑的地方，一定要记住！

---

### ⚠️ 注意事项 1：修改代码后必须重新打 JAR

**原因**：Docker 容器运行的是 `target/SupplyChain-1.0.0.jar`，代码修改后 JAR 不会自动更新。

**操作流程**：
```powershell
# 第一步：重新打包（每次改代码都要做）
$mvn = "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_321"
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
& $mvn clean package -DskipTests -q

# 第二步：重建镜像并重启容器（加 --build 才会用新 JAR）
docker-compose --progress plain up -d --build supply-chain-app
```

---

### ⚠️ 注意事项 2：不能同时跑本地 Spring Boot 和 Docker 容器（端口冲突）

**原因**：本地 `mvn spring-boot:run` 和 `sc-app` 容器都占用 `8091` 端口，二选一。

```powershell
# 查看谁占用了 8091
netstat -ano | findstr ":8091"

# 停掉本地 java 进程（PID 替换为实际值）
Stop-Process -Id <PID> -Force
```

---

### ⚠️ 注意事项 3：端口映射容易搞混

| 服务 | Docker 容器内端口 | 宿主机映射端口 | 谁连哪个 |
|------|-----------------|--------------|---------|
| MySQL | 3306 | 3307 | 容器内 Spring Boot → `mysql:3306`；外部工具（Navicat）→ `localhost:3307` |
| Redis | 6379 | 6380 | 容器内 Spring Boot → `redis:6379`；外部 redis-cli → `localhost:6380` |
| Kafka | 9092 | 9092 | 容器内 Spring Boot → `kafka:29092`；外部工具 → `localhost:9092` |
| Spring Boot | 8091 | 8091 | 浏览器 → `localhost:8091` |

---

### ⚠️ 注意事项 4：YAML 不允许重复的 Key

**原因**：`application.yml` 是 YAML 格式，同一层级不能有两个相同的 Key（如两个 `logging:`），否则 Spring Boot 启动报 `DuplicateKeyException`。

**检查方法**：修改 `application.yml` 后，搜一下有没有重复的顶级 Key：
```powershell
Select-String "^[a-z]" src\main\resources\application.yml | ForEach-Object { $_.Line }
# 看输出里有没有同名的行
```

---

### ⚠️ 注意事项 5：StringRedisTemplate vs RedisTemplate 的区别

**一句话**：凡是要被 Lua 脚本读取的 Key，必须用 `StringRedisTemplate`，不能用 `RedisTemplate<String,Object>`。

**原因**：
- `StringRedisTemplate` → Redis 存 `"100"`（纯字符串）→ Lua `tonumber()` 能解析 ✅
- `RedisTemplate<String,Object>` 用 Jackson → Redis 存 `["java.lang.Long",100]` → Lua `tonumber()` 返回 nil ❌

---

### ⚠️ 注意事项 6：Knife4j 4.x 用 OpenAPI 3 注解，不再支持 Swagger 2 注解

| Swagger 2（❌ 不支持）| OpenAPI 3（✅ 正确）|
|---------------------|-------------------|
| `@Api(tags = "xxx")` | `@Tag(name = "xxx")` |
| `@ApiOperation("xxx")` | `@Operation(summary = "xxx")` |
| `import io.swagger.annotations.*` | `import io.swagger.v3.oas.annotations.*` |

---

### ⚠️ 注意事项 7：Redis 缓存预热

首次入库会自动同步 Redis。但如果 Redis 重启（`docker-compose restart redis`）或清空（`FLUSHDB`），缓存会消失。第一次下单时 Lua 返回 `-2`，代码会自动从 DB 补充，通常不影响业务，但并发极高时可能有短暂"库存不足"误报。

**手动预热命令**：
```bash
curl -X POST http://localhost:8091/api/inventory/1/{skuId}/warmup
```

---

## 17. 未完成的功能（TODO）

> 记录还没做的事情，供后续迭代参考。

### 🔴 高优先级（影响核心功能）

| # | 功能 | 描述 | 涉及文件 |
|---|------|------|---------|
| 1 | **超卖并发演示** | 用 PowerShell 100 并发请求验证 Lua 防超卖效果 | 见第 9 节脚本 |
| 2 | **基础数据初始化** | 每次重置 DB 后，需要手动插入分类、仓库、门店数据 | 见第 8.1 节 SQL |

### 🟡 中优先级（Phase 2 功能外壳已预留）

| # | 功能 | 描述 | 当前状态 |
|---|------|------|---------|
| 3 | **骑手配送模块** | `DeliveryController` / `DeliveryServiceImpl` 接口外壳已存在，业务逻辑未实现 | 接口返回空/TODO |
| 4 | **金蝶云财务对接** | `KingdeeController` / `KingdeeDataSyncJob` 外壳已存在，HTTP 推送凭证逻辑未实现 | `kingdee.enabled=false` |
| 5 | **Redisson 分布式锁** | 补货审批等高并发写操作建议加分布式锁，依赖已注释 | `pom.xml` 注释中 |

### 🟢 低优先级（工程化完善）

| # | 功能 | 描述 | 备注 |
|---|------|------|------|
| 6 | **JMeter 压测报告** | 用 JMeter 生成并发测试的 HTML 聚合报告 | 见第 9.3 节 |
| 7 | **Kubernetes 部署** | 把 docker-compose 转为 k8s Deployment + Service | `k8s/` 目录已预留 |
| 8 | **CI/CD 流水线** | GitHub Actions 自动构建 + 推送镜像到 Docker Hub | 未创建 `.github/workflows/` |
| 9 | **生产环境配置** | 密码改用 Secrets 管理、Knife4j 开启密码保护、日志级别改 INFO | `application-prod.yml` 未创建 |
| 10 | **数据库迁移工具** | 用 Flyway/Liquibase 管理 DDL 版本，替代手动 `init.sql` | 未引入 |
| 11 | **单元测试** | Service 层核心逻辑（防超卖、FIFO、对账）缺乏单元测试 | `src/test/` 目录基本为空 |

---

### 下次开发从哪里开始？

```
最建议先做：
1. 执行超卖演示（第9节），验证防超卖功能 → 是最重要的技术亮点
2. 骑手配送模块业务实现 → 补全履约流程

等待实现的接口（搜索 "TODO" 或 "Phase2"）：
grep -r "TODO\|Phase2\|Phase 2" src/main/java/
```

---

*文档版本: v1.2 | 2026-06-01 | 碧桂园旺生活 O2O 供应链中台*
