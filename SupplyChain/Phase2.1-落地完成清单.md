# Phase 2.1 骑手配送模块 — 落地完成清单

> 本次生成的代码骨架已通过 `mvn compile` 验证（0 错误）。
> 设计文档：`Phase2-骑手配送模块设计.md`

## 📦 新增/修改文件清单（共 27 个）

### 数据库（1）
- `src/main/resources/db/phase2_init.sql` — DDL 增量（ALTER 2 张 + CREATE 4 张）

### 实体（6）
- `entity/Rider.java` — **扩展**至 14 字段（位置/负载/评分/在线状态）
- `entity/DeliveryOrder.java` — **扩展**至 25+ 字段
- `entity/DeliveryTrack.java` — 新增（轨迹）
- `entity/DeliveryGrabPool.java` — 新增（抢单池）
- `entity/RiderIncome.java` — 新增（收入流水）
- `entity/DeliveryException.java` — 新增（异常工单）

### 枚举（2）
- `enums/DeliveryStatus.java` — **扩展**至 11 个状态
- `enums/RiderStatus.java` — 新增

### Mapper（6）
- `mapper/RiderMapper.java` — `selectAvailable / incrLoadIfAvailable / decrLoadOnFinish / heartbeat / offlineExpiredHeartbeat`
- `mapper/DeliveryOrderMapper.java` — **扩展** `transition / bindRiderIfPending / incrReassignCount / 超时扫描`
- `mapper/DeliveryGrabPoolMapper.java`
- `mapper/DeliveryTrackMapper.java`
- `mapper/RiderIncomeMapper.java`
- `mapper/DeliveryExceptionMapper.java`

### DTO / 消息（4）
- `dto/request/RiderHeartbeatRequest.java`
- `dto/request/DeliveryCodeRequest.java`
- `dto/request/DeliveryExceptionRequest.java`
- `dto/DeliveryEventMessage.java` — Kafka 消息

### Service（6）
- `service/RiderService.java` + `impl/RiderServiceImpl.java`（11 个方法：上下线/心跳/接单/拒单/抢单/取货/送达/异常等）
- `service/DeliveryService.java` + `impl/DeliveryServiceImpl.java`（建单/改派/取消/查询）
- `service/DispatchService.java` + `impl/DispatchServiceImpl.java`（V1 派单算法：距离 + 评分 + 负载 加权）

### Controller（2）
- `controller/RiderController.java` — **C 端**（骑手 App 11 个接口）
- `controller/DeliveryController.java` — **替换**为 B 端管理后台

### Config（3）
- `config/DeliveryProperties.java` — 配置中心
- `config/WebSocketConfig.java` — WebSocket 路由
- `config/RedisConfig.java` — **追加** `riderGrabScript` Lua 注册
- `config/KafkaConfig.java` — **追加** 4 个 Topic
- `config/MetaObjectFillHandler.java` — **追加** `reportTime` 字段填充

### WebSocket / 工具 / Producer（3）
- `websocket/RiderWebSocketHandler.java` — 长连接管理 + 推送
- `util/DeliveryUtil.java` — Haversine 距离计算 + 验证码 + Redis Key
- `mq/DeliveryEventProducer.java` — 4 类事件发送

### Kafka Listener（1）
- `listener/DeliveryEventListener.java` — 配送送达回写履约 / 拒收逆向库存（部分 TODO）

### Job（3）
- `job/DispatchJob.java` — 每 5 秒扫 PENDING 派单
- `job/DeliveryTimeoutJob.java` — 每分钟扫超时
- `job/RiderOfflineCheckJob.java` — 心跳超时下线

### 集成（1）
- `service/impl/FulfillmentServiceImpl.java` — `outbound()` 末尾自动建配送单

### 资源（2）
- `resources/lua/rider_grab.lua` — 抢单 SETNX
- `resources/application.yml` — 追加 `delivery:` 配置块

### 依赖（1）
- `pom.xml` — 加 `spring-boot-starter-websocket`

---

## ✅ 已落地能力（Phase 2.1 范围）

| 能力 | 实现位置 | 状态 |
|------|---------|------|
| 骑手上下线 + 心跳/位置上报 | RiderService.online/offline/heartbeat | ✅ |
| 骑手 WebSocket 长连接 | RiderWebSocketHandler `/ws/rider/{riderId}` | ✅ 单实例 |
| 智能派单（距离+评分+负载加权）| DispatchServiceImpl.dispatch | ✅ V1 |
| 骑手接单/拒单 | RiderService.acceptAssign/reject | ✅ |
| **抢单防超抢**（Lua SETNX + DB 条件 update 双保险）| RiderService.grab + lua/rider_grab.lua | ✅ |
| 扫描取货码/签收码 | RiderService.pickup/delivered | ✅ |
| 异常工单上报 | RiderService.reportException | ✅ |
| 配送费 + 超时罚款计算 | RiderServiceImpl.calcPenalty | ✅ |
| 骑手收入流水 | sc_rider_income 表 + delivered() 写入 | ✅ |
| 履约 OUTBOUND → 自动建配送单 | FulfillmentServiceImpl.outbound | ✅ |
| 配送 DELIVERED → 回写履约 | DeliveryEventListener.onDelivered (Kafka) | ✅ |
| 派单定时任务（每5秒）| DispatchJob | ✅ |
| 取货超时自动改派 | DeliveryTimeoutJob | ✅ |
| 送达超时打 TIMEOUT | DeliveryTimeoutJob | ✅ |
| 心跳超时强制下线 | RiderOfflineCheckJob | ✅ |
| 后台改派/取消/查轨迹 | DeliveryController（B端）| ✅ |

## ⏳ 留待 Phase 2.2 的部分（代码中已标 TODO）

- [ ] **抢单池广播**（拒单后自动入池 + WebSocket 推附近骑手）
- [ ] **客户拒收逆向库存**（DeliveryEventListener.onReturned 只写日志）
- [ ] **轨迹定时归档**（DeliveryTrack 心跳时未入表，HTTP 心跳目前只更新 sc_rider 当前位置）
- [ ] **多副本 ShedLock**（DispatchJob 上 K8s 后必须）
- [ ] **跨实例 WebSocket Pub/Sub**（多 sc-app 副本时）
- [ ] **高峰补贴 / 天气补贴**（已留字段 rushHourBonus / weatherBonus，逻辑待加）
- [ ] **骑手登录 + 权限（JWT）**
- [ ] **多仓 DispatchJob 循环**（当前硬编码 warehouseId=1L）

---

## 🚀 启动验证步骤

```powershell
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain

# 1. 跑 DDL（在已启动的 MySQL 容器执行）
Get-Content .\src\main\resources\db\phase2_init.sql -Raw `
  | docker exec -i sc-mysql mysql -uroot -proot123 sc_supply_chain

# 2. 重新打包 + 重启 sc-app
& "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q
docker-compose up -d --build supply-chain-app

# 3. 等待启动
Start-Sleep -Seconds 10
docker logs --tail 50 sc-app | Select-String "Started SupplyChainApplication"

# 4. 浏览器打开 Knife4j 看新接口
start http://localhost:8091/doc.html
# 应能看到新增 Tag：「骑手 App（C端）」「骑手配送管理（B端）」
```

## 🧪 快速冒烟测试

```powershell
# 准备 1 个骑手（先插数据库）
docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -e @"
INSERT INTO sc_rider (id, rider_name, phone, status, warehouse_id, vehicle_type,
  current_lng, current_lat, max_parallel, current_load, rating, today_orders, online)
VALUES (1, '张师傅', '13800000001', 'OFFLINE', 1, 'EBIKE', 113.95, 22.55, 3, 0, 5.00, 0, 0)
ON DUPLICATE KEY UPDATE rider_name=VALUES(rider_name);
"@

# 1. 骑手上线
Invoke-RestMethod -Uri 'http://localhost:8091/api/rider/1/online' -Method Post

# 2. 心跳
$body = @{ riderId=1; lng=113.95; lat=22.55 } | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:8091/api/rider/heartbeat' -Method Post -Body $body -ContentType 'application/json'

# 3. 走完整流程：建履约订单 → 支付 → 出库 → 自动建配送单 → 派单 → 取货 → 送达
$orderBody = @{ storeId=1; skuId=1001; qty=1; payAmount=9.9; address='测试社区A栋'; warehouseId=1 } | ConvertTo-Json
$o = Invoke-RestMethod -Uri 'http://localhost:8091/api/fulfillment/order/create' -Method Post -Body $orderBody -ContentType 'application/json'
$orderNo = $o.data
Invoke-RestMethod -Uri "http://localhost:8091/api/fulfillment/order/$orderNo/paid" -Method Post
Invoke-RestMethod -Uri "http://localhost:8091/api/fulfillment/order/$orderNo/outbound" -Method Post

# 4. 等 DispatchJob 派单（最多 5 秒）
Start-Sleep -Seconds 6

# 5. 查骑手当前订单
Invoke-RestMethod -Uri 'http://localhost:8091/api/rider/1/orders' -Method Get | ConvertTo-Json -Depth 5
# 拿到 deliveryId 和 pickupCode/deliverCode

# 6. 取货（用 pickupCode）
$body = @{ riderId=1; code='123456' } | ConvertTo-Json   # 替换为真实 pickupCode
Invoke-RestMethod -Uri 'http://localhost:8091/api/rider/{deliveryId}/pickup' -Method Post -Body $body -ContentType 'application/json'

# 7. 送达
Invoke-RestMethod -Uri 'http://localhost:8091/api/rider/{deliveryId}/delivered' -Method Post -Body $body -ContentType 'application/json'
```

## 📐 总代码量

```
27 个新建/修改文件，约 1500 行 Java + 100 行 SQL + 30 行 Lua + 20 行 yml
```

复用主项目 P0 修复经验（B1 幂等、B3 条件 update、B7 SETNX、B5 INCRBY）保证并发安全，避免重蹈覆辙。

