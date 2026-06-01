# 旺生活 O2O 供应链中台 — API 测试指南

> 完整走一遍系统流程 + 模拟超卖并发场景

---

## 目录

1. [环境准备：用 Docker 启动中间件](#1-环境准备用-docker-启动中间件)
2. [启动 Spring Boot 应用](#2-启动-spring-boot-应用)
3. [完整业务流程 API 演练（curl）](#3-完整业务流程-api-演练curl)
4. [超卖模拟：用 ApacheBench 发起高并发](#4-超卖模拟用-apachebench-发起高并发)
5. [超卖模拟：用 JMeter 高并发测试](#5-超卖模拟用-jmeter-高并发测试)
6. [查看结果验证](#6-查看结果验证)
7. [Knife4j Swagger UI 使用](#7-knife4j-swagger-ui-使用)
8. [常见问题排查](#8-常见问题排查)

---

## 1. 环境准备：用 Docker 启动中间件

### 1.1 前置要求

- Docker Desktop（Windows）已安装并运行 ✅（已检测到 v29.1.3）
- **注意**：你本机已有 Redis（`C:\WorkSoftware\Redis\Redis-5.0.14.1`）和 MySQL，启动 Docker 服务前先停掉本地的，或修改 `.env` 改端口：
  ```powershell
  # 停本地 Redis（如果在跑）
  Stop-Service "Redis" -ErrorAction SilentlyContinue
  # 或直接改 .env 把 REDIS_PORT 改成 6380
  ```

### 1.2 启动三大中间件

```bash
# 进入 SupplyChain 目录
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain

# 仅启动中间件（MySQL + Redis + Kafka + Kafka-UI）
docker-compose up -d mysql redis kafka kafka-ui

# 查看启动状态（等所有 STATUS 变成 healthy）
docker-compose ps
```

**期望输出（约 30 秒后）：**
```
NAME          IMAGE                STATUS
sc-mysql      mysql:8.0.33         Up (healthy)
sc-redis      redis:7-alpine       Up (healthy)
sc-kafka      bitnami/kafka:3.6    Up (healthy)
sc-kafka-ui   provectuslabs/...    Up
```

### 1.3 验证各服务

```bash
# 验证 MySQL — 连接并查看数据库
docker exec -it sc-mysql mysql -uroot -p123456 -e "SHOW DATABASES; USE sc_supply_chain; SHOW TABLES;"

# 验证 Redis
docker exec -it sc-redis redis-cli ping
# 期望输出: PONG

# 验证 Kafka
docker exec -it sc-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
# 期望: 空列表（Topics 由 Spring Boot 启动时自动创建）
```

### 1.4 访问 Kafka UI

打开浏览器访问：**http://localhost:8080**

可以查看 Topics、消息内容、Consumer Groups 状态。

---

## 2. 启动 Spring Boot 应用

### 方式 A：IDE 直接运行（推荐本地开发）

在 IDEA 里运行 `SupplyChainApplication.main()`，确保 Run Configuration 里有环境变量：
```
DB_HOST=localhost
REDIS_HOST=localhost
KAFKA_SERVERS=localhost:9092
```

### 方式 B：Maven 命令行

> 本机 Maven 位于 IntelliJ IDEA 内置目录，可直接调用：

```powershell
# 设置 Maven 路径（一次性）
$mvn = "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_321"

# 在 SupplyChain 目录下启动
Set-Location "C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain"
& $mvn spring-boot:run
```

### 方式 C：Docker 容器运行（使用 --profile app）

```powershell
# 先打包（用 IntelliJ 内置 Maven）
$mvn = "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_321"
& $mvn clean package -DskipTests

# 启动全部（含 App）
docker-compose --profile app up -d

# 查看 App 日志
docker logs -f sc-app
```

### 启动成功标志

控制台出现：
```
Supply Chain Started Successfully
```

---

## 3. 完整业务流程 API 演练（curl）

> 按顺序执行，每步依赖上一步的返回值

### 基础 URL

```
BASE_URL=http://localhost:8091
```

---

### Step 1：创建商品类目

```bash
# 商品类目（直接插入测试数据，或通过 API 预先准备）
# 通过 MySQL 直接插入初始数据：
docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain -e "
INSERT IGNORE INTO sc_category VALUES (1, 0, '生鲜食品', 1, 0, NOW(), NULL);
INSERT IGNORE INTO sc_category VALUES (2, 1, '乳制品', 2, 0, NOW(), NULL);
INSERT IGNORE INTO sc_category VALUES (3, 0, '粮油干货', 1, 1, NOW(), NULL);

INSERT IGNORE INTO sc_warehouse VALUES (1, '碧桂园中央仓', '广州市天河区xx路', 'MAIN', NOW());

INSERT IGNORE INTO sc_store VALUES (1, '碧桂园天悦社区店', 100, '广州市天河区', '13800138000', 'OPEN', NOW(), 0);
"
```

---

### Step 2：创建 SPU（商品）

```bash
curl -X POST http://localhost:8091/api/product/spu \
  -H "Content-Type: application/json" \
  -d '{
    "spuName": "蒙牛纯牛奶",
    "categoryId": 2,
    "brand": "蒙牛",
    "freshType": "CHILLED",
    "description": "250ml*12盒/箱，低温保存"
  }'
```

**期望响应：**
```json
{"code":200,"message":"success","data":1234567890123}
```
> 记录返回的 `data` 值作为 `SPU_ID`，例如 `1234567890123`

---

### Step 3：创建 SKU（规格）

```bash
curl -X POST http://localhost:8091/api/product/sku \
  -H "Content-Type: application/json" \
  -d '{
    "spuId": 1234567890123,
    "skuName": "蒙牛纯牛奶250ml*12盒",
    "skuAttrs": "[{\"attrKey\":\"容量\",\"attrVal\":\"250ml\"},{\"attrKey\":\"包装\",\"attrVal\":\"12盒/箱\"}]",
    "price": 39.90,
    "weight": 3.0,
    "imgUrl": "https://example.com/milk.jpg"
  }'
```

**记录返回的 `SKU_ID`，例如 `1234567890124`**

---

### Step 4：SPU 上架

```bash
curl -X PUT http://localhost:8091/api/product/spu/1234567890123/on-sale
```

**期望：** `{"code":200,"message":"success","data":null}`

---

### Step 5：入库（INBOUND）

> 入库 100 箱牛奶，效期 30 天后

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

---

### Step 6：验证库存

```bash
# 查看 DB 库存
curl http://localhost:8091/api/inventory/1/1234567890124

# 查看 Redis 缓存库存（应该是 100）
curl http://localhost:8091/api/inventory/1/1234567890124/redis
```

**期望：**
```json
// DB 库存
{"code":200,"data":{"availableQty":100,"lockedQty":0,"totalQty":100}}

// Redis 库存
{"code":200,"data":100}
```

---

### Step 7：创建订单（下单 2 箱）

```bash
curl -X POST http://localhost:8091/api/fulfillment/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "storeId": 1,
    "skuId": 1234567890124,
    "qty": 2,
    "payAmount": 79.80,
    "address": "广州市天河区碧桂园天悦花园1栋101",
    "warehouseId": 1
  }'
```

**期望：**
```json
{"code":200,"message":"success","data":"ORD-XXXXXXXXXXXXXXXX"}
```
> 记录订单号 `ORDER_NO`，例如 `ORD-ABCD1234EFGH5678`

---

### Step 8：验证下单后库存变化

```bash
# Redis 应该从 100 减到 98（Lua Script 原子预扣）
curl http://localhost:8091/api/inventory/1/1234567890124/redis

# 等待约 1 秒（Kafka 异步消费）再查 DB
sleep 1
curl http://localhost:8091/api/inventory/1/1234567890124
# DB available_qty=98, locked_qty=2
```

---

### Step 9：支付

```bash
curl -X POST http://localhost:8091/api/fulfillment/order/ORD-ABCD1234EFGH5678/paid
```

---

### Step 10：出库（FIFO 分配批次）

```bash
curl -X POST http://localhost:8091/api/fulfillment/order/ORD-ABCD1234EFGH5678/outbound
```

**出库后（等待 Kafka）：**
- `sc_inventory.locked_qty` 减 2
- `sc_inventory.total_qty` 减 2
- `sc_inventory_batch` 中 `BATCH-20260529-001.remain_qty` 减 2

---

### Step 11：查看订单状态

```bash
curl http://localhost:8091/api/fulfillment/order/ORD-ABCD1234EFGH5678
```

**期望：**
```json
{
  "code": 200,
  "data": {
    "orderNo": "ORD-ABCD1234EFGH5678",
    "status": "OUTBOUND",
    "qty": 2
  }
}
```

---

### Step 12：测试取消订单（先创建一个新订单再取消）

```bash
# 创建新订单
curl -X POST http://localhost:8091/api/fulfillment/order/create \
  -H "Content-Type: application/json" \
  -d '{"storeId":1,"skuId":1234567890124,"qty":3,"payAmount":119.70,"address":"测试地址","warehouseId":1}'
# 记录新 ORDER_NO2

# 取消订单
curl -X POST http://localhost:8091/api/fulfillment/order/ORD-新订单号/cancel

# 验证 Redis 库存恢复
curl http://localhost:8091/api/inventory/1/1234567890124/redis
# 取消后，Redis 从 96 恢复到 99（98-3=95，cancel 后 95+3=98，原来因上一步出库已减 2，实际是 96+3=99）
```

---

### Step 13：设置补货规则

```bash
curl -X POST http://localhost:8091/api/replenishment/rule \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": 1234567890124,
    "warehouseId": 1,
    "minQty": 20,
    "replenishQty": 100
  }'
```

---

### Step 14：手动触发对账

```bash
curl -X POST http://localhost:8091/api/inventory/reconcile
```

---

## 4. 超卖模拟：用 ApacheBench 发起高并发

> ApacheBench（ab）是 Apache 自带工具，Windows 可以用 WSL 或直接安装。

### 4.1 准备场景

**目标**：库存只有 10 件，100 个并发请求同时下单（每单 1 件），验证最终只成功 10 笔。

#### 先设置库存为 10 件

```bash
# 先清零库存（通过入库一个新批次覆盖）
# 重置 Redis（手动 SET）
docker exec -it sc-redis redis-cli -n 1 SET sc:inventory:available:1:SKU_ID 10

# 更新 DB
docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain -e "
UPDATE sc_inventory SET available_qty=10, locked_qty=0, total_qty=10
WHERE sku_id=SKU_ID AND warehouse_id=1;
"
```

#### 创建请求 Body 文件

Windows PowerShell：
```powershell
$body = '{"storeId":1,"skuId":SKU_ID,"qty":1,"payAmount":39.90,"address":"测试地址","warehouseId":1}'
$body | Out-File -FilePath "C:\temp\order_body.json" -Encoding UTF8
```

#### 用 ab 发起 100 个并发

```bash
# WSL 或 Git Bash 中执行
ab -n 100 -c 100 \
   -p /tmp/order_body.json \
   -T "application/json" \
   http://localhost:8091/api/fulfillment/order/create
```

#### 验证结果

```bash
# Redis 库存应该是 0（精确扣完）
docker exec -it sc-redis redis-cli -n 1 GET sc:inventory:available:1:SKU_ID

# 统计成功下单数（应该恰好 10 笔）
docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain -e "
SELECT status, COUNT(*) as cnt
FROM sc_fulfillment_order
WHERE sku_id=SKU_ID
GROUP BY status;
"
# 期望: PENDING=10

# 查看库存日志
docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain -e "
SELECT op_type, COUNT(*), SUM(delta_qty) as total_delta
FROM sc_inventory_log
WHERE sku_id=SKU_ID
GROUP BY op_type;
"
```

**预期结论**：
- `PENDING` 订单恰好 **10** 笔 ✅
- 另外 **90** 笔请求返回 HTTP 400 `{"code":4001,"message":"SKU[xxx] 库存不足"}`
- **零超卖** ✅

---

## 5. 超卖模拟：用 JMeter 高并发测试

> JMeter 界面更直观，支持图形化报告

### 5.1 下载安装

1. 下载 [Apache JMeter 5.6.x](https://jmeter.apache.org/download_jmeter.cgi)
2. 解压，运行 `bin\jmeter.bat`

### 5.2 创建测试计划（步骤）

#### A. 新建 Test Plan → 右键 Add → Thread Group

| 参数 | 值 | 说明 |
|------|-----|------|
| Number of Threads | `100` | 模拟 100 个并发用户 |
| Ramp-up Period | `1` | 1 秒内全部启动（模拟秒杀） |
| Loop Count | `1` | 每用户发一次请求 |

#### B. Thread Group → Add → Sampler → HTTP Request

| 参数 | 值 |
|------|-----|
| Server Name | `localhost` |
| Port | `8091` |
| Method | `POST` |
| Path | `/api/fulfillment/order/create` |
| Body Data | 见下方 |

**Body Data（注意替换 SKU_ID）：**
```json
{"storeId":1,"skuId":1234567890124,"qty":1,"payAmount":39.90,"address":"广州市天河区测试","warehouseId":1}
```

#### C. 添加 Header Manager

Thread Group → Add → Config Element → HTTP Header Manager

| Name | Value |
|------|-------|
| Content-Type | `application/json` |

#### D. 添加聚合报告

Thread Group → Add → Listener → Aggregate Report

#### E. 添加响应断言（可选）

Add → Assertions → Response Assertion → Response Code = `200` or `400`

### 5.3 运行

1. 先准备库存 10 件（见上方 4.1 步骤）
2. 点击 **Run** ▶
3. 查看 Aggregate Report：
   - Throughput（吞吐量）
   - Error% — 预期约 90% 报错（库存不足）

### 5.4 JMeter 超卖验证结果

```
正常： 10 笔成功（HTTP 200）
报错： 90 笔失败（HTTP 400，库存不足）
Redis 库存最终 = 0
DB 订单数 = 10

结论：Lua Script 原子操作，完全防止超卖 ✅
```

### 5.5 对比实验：关掉 Lua，用普通 SET 看超卖

> 为了演示超卖危害，可以临时修改 lockStock 用 GET+SET 替代 Lua：

```java
// 临时注释 Lua，改为非原子操作（仅演示，生产绝不这样做）
Long current = Long.parseLong(
    stringRedisTemplate.opsForValue().getAndSet(key, "0").toString());
// 这里 GET 和 SET 中间有窗口，100个并发全都读到 10，全部认为"有货"
```

结果：100 笔全部成功（超卖了 90 件）。  
这就是为什么必须用 Lua Script 的直接原因。

---

## 6. 查看结果验证

### 6.1 查看 Kafka 消息流

打开 **http://localhost:8080**（Kafka UI）：
- Topics → `sc.inventory.deduct` → 查看消息内容
- Consumer Groups → `sc-inventory-deduct` → 查看 Lag（积压量）

### 6.2 查看 Redis 实时库存

```bash
# 连接 Redis CLI（database 1）
docker exec -it sc-redis redis-cli -n 1

# 查看库存
GET sc:inventory:available:1:1234567890124

# 查看对账差异集合
SMEMBERS sc:inventory:reconcile:diff

# 查看 FIFO ZSet
ZRANGE sc:inventory:batch:1:1234567890124 0 -1 WITHSCORES
```

### 6.3 查看 MySQL 数据

```bash
docker exec -it sc-mysql mysql -uroot -p123456 sc_supply_chain
```

```sql
-- 查看库存状态
SELECT s.sku_name, i.available_qty, i.locked_qty, i.total_qty
FROM sc_inventory i JOIN sc_sku s ON i.sku_id = s.id
WHERE i.warehouse_id = 1;

-- 查看批次 FIFO 状态
SELECT batch_no, inbound_qty, remain_qty, inbound_time, expire_date, status
FROM sc_inventory_batch
ORDER BY inbound_time ASC;

-- 查看订单汇总
SELECT status, COUNT(*) FROM sc_fulfillment_order GROUP BY status;

-- 查看库存操作日志
SELECT op_type, delta_qty, ref_no, create_time
FROM sc_inventory_log
ORDER BY create_time DESC
LIMIT 20;

-- 查看效期预警
SELECT * FROM sc_expiry_warning WHERE is_handled = 0 ORDER BY warn_time DESC;
```

### 6.4 查看 Druid 监控

打开 **http://localhost:8091/druid/** （用户名/密码：admin/admin123）
- 可以看到 SQL 执行情况、慢 SQL、连接池状态

---

## 7. Knife4j Swagger UI 使用

打开 **http://localhost:8091/doc.html**

所有 API 都有文档，可以直接在界面上调试：

| 模块 | Controller | 说明 |
|------|-----------|------|
| 商品中心 | `/api/product/*` | SPU/SKU 管理 |
| 库存管理 | `/api/inventory/*` | 入库、查询、预热、对账 |
| O2O 履约 | `/api/fulfillment/*` | 下单、支付、出库、取消 |
| 自动补货 | `/api/replenishment/*` | 规则管理 |

---

## 8. 常见问题排查

### 问题 1：Spring Boot 启动报 Kafka 连接失败

```
org.apache.kafka.common.errors.TimeoutException: Topic not present in metadata
```

**解决：**
```bash
# 确认 Kafka 正常启动
docker-compose ps
# 等待 sc-kafka STATUS = Up (healthy)，大约需要 30 秒

# 检查端口
docker exec -it sc-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

---

### 问题 2：下单报错 "库存不足" 但明明已入库

**原因**：Redis 缓存未预热（`inventory:available` key 不存在，Lua 返回 -2，程序理应 warmup 后重试）

**解决：** 调用预热接口
```bash
curl -X POST http://localhost:8091/api/inventory/1/SKU_ID/warmup

# 然后重新查看 Redis
curl http://localhost:8091/api/inventory/1/SKU_ID/redis
```

---

### 问题 3：MySQL 表不存在

```
Table 'sc_supply_chain.sc_xxx' doesn't exist
```

**解决：** 手动执行 init.sql
```bash
docker exec -i sc-mysql mysql -uroot -p123456 sc_supply_chain < src/main/resources/db/init.sql
```

---

### 问题 4：Kafka 消息消费后 DB 库存没变

**排查：**
```bash
# 查看消费者 Lag（Kafka UI 或命令行）
docker exec -it sc-kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group sc-inventory-deduct
```

如果 Lag > 0，说明消费者正在积压，等待处理。

---

### 问题 5：Windows 没有 ab 工具

**方案 A**：用 PowerShell + Parallel（模拟并发）

```powershell
$body = '{"storeId":1,"skuId":1234567890124,"qty":1,"payAmount":39.90,"address":"测试","warehouseId":1}'
$jobs = 1..100 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($b)
        Invoke-RestMethod -Method POST `
          -Uri "http://localhost:8091/api/fulfillment/order/create" `
          -ContentType "application/json" `
          -Body $b
    } -ArgumentList $body
}
$jobs | Wait-Job | Receive-Job | Select-Object data | Group-Object data | Format-Table
```

**方案 B**：安装 WSL（Windows Subsystem for Linux），用 `apt install apache2-utils`

**方案 C**：用 JMeter（推荐，有 GUI 报告）

---

## 附录：完整 API 地址汇总

| 功能 | Method | URL |
|------|--------|-----|
| 创建 SPU | POST | `/api/product/spu` |
| SPU 上架 | PUT | `/api/product/spu/{spuId}/on-sale` |
| SPU 下架 | PUT | `/api/product/spu/{spuId}/off-sale` |
| 创建 SKU | POST | `/api/product/sku` |
| 商品入库 | POST | `/api/inventory/inbound` |
| 查 DB 库存 | GET | `/api/inventory/{warehouseId}/{skuId}` |
| 查 Redis 库存 | GET | `/api/inventory/{warehouseId}/{skuId}/redis` |
| 预热 Redis | POST | `/api/inventory/{warehouseId}/{skuId}/warmup` |
| 手动对账 | POST | `/api/inventory/reconcile` |
| **下单（核心）** | **POST** | **`/api/fulfillment/order/create`** |
| 支付回调 | POST | `/api/fulfillment/order/{orderNo}/paid` |
| 出库（FIFO） | POST | `/api/fulfillment/order/{orderNo}/outbound` |
| 取消订单 | POST | `/api/fulfillment/order/{orderNo}/cancel` |
| 查询订单 | GET | `/api/fulfillment/order/{orderNo}` |
| 效期预警列表 | GET | `/api/fulfillment/expiry/warning/list` |
| 创建补货规则 | POST | `/api/replenishment/rule` |
| 查看补货单 | GET | `/api/replenishment/order/list` |

---

*版本：v1.1 | 2026-05-29 | 旺生活 O2O 供应链中台*




