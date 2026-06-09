# 🛵 Phase 2 — 骑手配送 & 抢单/派单模块详细设计

> 本文档是对 `plan-supplyChain.prompt.md` "骑手配送（Phase 2 外壳预留）"小节的**深化方案**。
> 现有外壳（`Rider.java` / `DeliveryOrder.java` / `DeliveryController.java`）字段单薄、缺关键场景，本文逐项补齐。

---

## 1. 当前外壳的不足（先看清差距）

| 模块 | 现有 | 缺失（必补） |
|------|------|-------------|
| `sc_rider` | id / name / phone / status(IDLE/BUSY/OFFLINE) | 当前位置经纬度、所属仓库、最大并行单数、车型、评分、当日累计单数、装备 ID、是否在线、最后心跳时间 |
| `sc_delivery_order` | id / fulfillmentOrderId / riderId / status / 时间字段 / failReason | **取货地址、收件地址、起止经纬度、距离、预估送达、超时阈值、取货码、签收码、配送费、超时罚款、客户备注、改派历史** |
| `DeliveryController` | assign / pickup / delivered / get | 缺：**抢单池查询、抢单、拒单、改派、骑手位置上报、客户实时查询、超时重派**、骑手当日单/收入查询 |
| 状态机枚举 | PENDING/ASSIGNED/PICKING/IN_TRANSIT/DELIVERED/FAILED | 缺：`GRABBING`（抢单池）/ `REJECTED`（骑手拒单）/ `REASSIGNED`（已改派）/ `RETURNED`（异常退回）/ `TIMEOUT`（超时未送达） |
| 表 | sc_rider / sc_delivery_order | 缺：**`sc_delivery_track`（轨迹）、`sc_delivery_grab_pool`（抢单池）、`sc_rider_income`（骑手收入流水）、`sc_rider_position`（实时位置 / Redis 替代）、`sc_delivery_exception`（异常工单）** |
| 配置 | 无 | 派单半径、抢单超时、取货超时、配送超时、骑手最大并行单、距离计费阶梯 |
| 集成点 | 无 | 履约 `OUTBOUND` → 自动触发派单（Kafka）；`DELIVERED` → 回写履约状态 |
| 通信 | 无 | 骑手 App ↔ 后端：WebSocket / 长轮询 / 推送（位置 + 抢单广播 + 改派通知） |

---

## 2. 业务模式选型：先决策"抢单 vs 派单"

这是 Phase 2 第一个必须决策的问题，不同模式直接影响表结构、接口、并发设计：

| 模式 | 美团/饿了么 | 顺丰 | 滴滴外卖 | 推荐选型 |
|------|-----------|------|---------|---------|
| **A. 强派单**（系统直接指派，骑手不能拒）| 早期众包 | ✅ 顺丰直营 | - | 适合自营骑手、薪资保底 |
| **B. 抢单**（系统广播，骑手抢，先到先得）| - | - | ✅ | 适合众包，激励高效骑手 |
| **C. 派单+可拒**（系统智能指派，骑手可拒1次）| ✅ 美团 | - | - | **旺生活推荐** |
| **D. 混合**（抢单池兜底+智能派单）| 大平台 | - | - | Phase 3 |

**推荐 C 模式（派单+可拒）原因**：
- 旺生活骑手是社区签约，强行派单影响士气；纯抢单又有"老骑手抢光好单、新骑手没单跑"问题
- C 模式既保派单效率，又给骑手一次拒绝权（拒单后转入抢单池广播）

---

## 3. 完整数据模型（DDL 增量）

### 3.1 `sc_rider` 表补字段

```sql
ALTER TABLE sc_rider
  ADD COLUMN warehouse_id BIGINT COMMENT '所属仓库（限制只接该仓订单）',
  ADD COLUMN vehicle_type VARCHAR(20) DEFAULT 'EBIKE' COMMENT 'EBIKE/MOTOR/CAR/WALK',
  ADD COLUMN current_lng DECIMAL(10,7) COMMENT '当前经度（实时心跳更新）',
  ADD COLUMN current_lat DECIMAL(10,7) COMMENT '当前纬度',
  ADD COLUMN max_parallel INT NOT NULL DEFAULT 3 COMMENT '同时配送单上限（防止超载）',
  ADD COLUMN current_load INT NOT NULL DEFAULT 0 COMMENT '当前在途单数（高频读写，需要乐观锁/Redis）',
  ADD COLUMN rating DECIMAL(3,2) DEFAULT 5.00 COMMENT '骑手评分（影响派单优先级）',
  ADD COLUMN today_orders INT NOT NULL DEFAULT 0 COMMENT '今日已完成单（每天0点 reset）',
  ADD COLUMN online TINYINT NOT NULL DEFAULT 0 COMMENT '0=离线 1=在线',
  ADD COLUMN last_heartbeat DATETIME COMMENT '最后心跳时间，>5分钟自动转 OFFLINE',
  ADD INDEX idx_wh_status_online (warehouse_id, status, online);
```

### 3.2 `sc_delivery_order` 表补字段

```sql
ALTER TABLE sc_delivery_order
  -- 地址 + 经纬度（避免每次重新地理编码）
  ADD COLUMN pickup_address VARCHAR(300) COMMENT '取货地址（仓库/小店）',
  ADD COLUMN pickup_lng DECIMAL(10,7),
  ADD COLUMN pickup_lat DECIMAL(10,7),
  ADD COLUMN deliver_address VARCHAR(300) COMMENT '收件地址',
  ADD COLUMN deliver_lng DECIMAL(10,7),
  ADD COLUMN deliver_lat DECIMAL(10,7),
  -- 距离与时效
  ADD COLUMN distance_meters INT COMMENT '直线距离（米）',
  ADD COLUMN estimated_minutes INT COMMENT '预估配送时长',
  ADD COLUMN expected_pickup_time DATETIME COMMENT '应取货时间（超时重派）',
  ADD COLUMN expected_deliver_time DATETIME COMMENT '应送达时间（超时升级）',
  -- 验证码（防错送/冒领）
  ADD COLUMN pickup_code VARCHAR(8) COMMENT '取货码（4-6位，仓库工作人员核对）',
  ADD COLUMN deliver_code VARCHAR(8) COMMENT '签收码（短信发给客户）',
  -- 计费
  ADD COLUMN base_fee DECIMAL(8,2) COMMENT '基础配送费',
  ADD COLUMN distance_fee DECIMAL(8,2) COMMENT '距离附加费',
  ADD COLUMN penalty_fee DECIMAL(8,2) DEFAULT 0 COMMENT '超时罚款',
  ADD COLUMN final_fee DECIMAL(8,2) COMMENT '骑手最终所得',
  -- 异常 & 改派
  ADD COLUMN reassign_count INT NOT NULL DEFAULT 0 COMMENT '改派次数（>=3 自动转人工）',
  ADD COLUMN customer_remark VARCHAR(300) COMMENT '客户备注（如"放门口"）',
  -- 客户联系（脱敏）
  ADD COLUMN customer_phone_masked VARCHAR(20) COMMENT '虚拟号码或脱敏展示',
  ADD INDEX idx_rider_status (rider_id, status),
  ADD INDEX idx_status_expected (status, expected_deliver_time);
```

### 3.3 新增 5 张表

```sql
-- 配送轨迹（分钟级位置）
CREATE TABLE sc_delivery_track (
    id BIGINT PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    rider_id BIGINT,
    lng DECIMAL(10,7),
    lat DECIMAL(10,7),
    speed_kmh DECIMAL(5,2),
    report_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_delivery_time (delivery_id, report_time)
) COMMENT='骑手配送轨迹（30秒~1分钟一条，3个月归档）';

-- 抢单池（GRABBING 状态的订单广播）
CREATE TABLE sc_delivery_grab_pool (
    id BIGINT PRIMARY KEY,
    delivery_id BIGINT NOT NULL UNIQUE,
    warehouse_id BIGINT,
    broadcast_lng DECIMAL(10,7) COMMENT '广播中心点（仓库位置）',
    broadcast_radius_m INT DEFAULT 3000 COMMENT '广播半径（米）',
    expire_time DATETIME NOT NULL COMMENT '抢单池过期时间（如60秒）',
    grabbed_by BIGINT COMMENT '抢到的骑手ID（NULL=待抢）',
    grab_time DATETIME,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_wh_expire (warehouse_id, expire_time)
) COMMENT='抢单池（也可全部放 Redis ZSet，DB 仅做归档）';

-- 骑手收入流水（每送一单写一条）
CREATE TABLE sc_rider_income (
    id BIGINT PRIMARY KEY,
    rider_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    base_fee DECIMAL(8,2),
    distance_fee DECIMAL(8,2),
    rush_hour_bonus DECIMAL(8,2) DEFAULT 0 COMMENT '高峰补贴',
    weather_bonus DECIMAL(8,2) DEFAULT 0,
    penalty_fee DECIMAL(8,2) DEFAULT 0,
    total DECIMAL(8,2) NOT NULL,
    settle_status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/SETTLED',
    settle_time DATETIME,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rider_create (rider_id, create_time)
) COMMENT='骑手收入流水';

-- 配送异常工单
CREATE TABLE sc_delivery_exception (
    id BIGINT PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    exception_type VARCHAR(30) NOT NULL COMMENT 'PICKUP_TIMEOUT/DELIVER_TIMEOUT/CUSTOMER_NOT_AVAILABLE/GOODS_DAMAGED/RIDER_REJECTED/CUSTOMER_REJECTED',
    exception_detail VARCHAR(500),
    handler_id BIGINT COMMENT '处理人',
    handle_status VARCHAR(20) DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    handle_remark VARCHAR(500),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handle_time DATETIME,
    INDEX idx_status (handle_status)
) COMMENT='配送异常工单';

-- 骑手实时位置（也可用 Redis GEO，DB 仅留 1 分钟快照）
-- 推荐 Redis：GEOADD sc:rider:geo:{warehouseId} lng lat riderId
-- DB 表可选：
CREATE TABLE sc_rider_position_snapshot (
    rider_id BIGINT PRIMARY KEY,
    lng DECIMAL(10,7),
    lat DECIMAL(10,7),
    update_time DATETIME ON UPDATE CURRENT_TIMESTAMP
) COMMENT='骑手位置快照（仅供后台查看，不参与派单计算）';
```

---

## 4. 完整状态机（带超时分支）

```
                                                    ┌──[抢单超时,转人工]→ EXCEPTION
                                                    │
PENDING (履约系统出库 OUTBOUND 触发自动建配送单)
   │
   ├─[策略A 智能派单]→ ASSIGNED ──[骑手拒单]→ GRABBING ──[抢到]→ ASSIGNED
   │                       │                       │
   │                       │                       └─[60s无人抢]→ REASSIGNED→人工干预
   │                       │
   │                       ↓
   │                   [骑手接单后]
   │                       │
   │                       ↓
   │                   PICKING ──[取货超时,系统重派]→ REASSIGNED → 重新进 ASSIGNED
   │                       │
   │                       ↓
   │                  [扫描取货码]
   │                       │
   │                       ↓
   │                  IN_TRANSIT ──[配送超时,告警客服]→ TIMEOUT(仍可送达)
   │                       │
   │                       ├─[扫描签收码成功]→ DELIVERED → 回写履约 DELIVERED
   │                       │
   │                       ├─[客户拒收]→ RETURNED → 触发逆向库存（库存恢复，订单 REFUNDING）
   │                       │
   │                       └─[骑手异常,工单介入]→ EXCEPTION
   │
   └─[策略B 抢单模式]→ GRABBING → ASSIGNED → ...（同上）
```

**关键超时配置（写进 `application.yml`）：**

```yaml
delivery:
  grab-pool-expire-seconds: 60        # 抢单池超时
  pickup-timeout-minutes: 15          # 接单后未取货超时
  deliver-timeout-minutes: 45         # 取货后未送达超时
  reassign-max-count: 3               # 最大改派次数（超过转人工）
  rider-heartbeat-interval-seconds: 30
  rider-offline-threshold-seconds: 300
  broadcast-radius-meters: 3000       # 抢单广播半径
  rush-hours: ["11:00-13:00", "17:30-19:30"]
  rush-hour-bonus-yuan: 2.0
```

---

## 5. 接口清单（C端 + B端）

### 5.1 骑手 App 端接口（C端）

| 接口 | 用途 | 关键并发点 |
|------|------|-----------|
| `POST /api/rider/login` | 骑手登录（手机号+短信码）| - |
| `POST /api/rider/online` | 上线（status: OFFLINE→IDLE，启动心跳）| 同一骑手只能一处在线 |
| `POST /api/rider/heartbeat` | 心跳+位置上报（30s 一次）| 高频写入，建议入 Redis 不入 DB |
| `GET /api/rider/grab-pool?lng=&lat=` | **拉取附近抢单池**（按距离/价格排序） | Redis ZSet 取候选 |
| `POST /api/rider/grab/{deliveryId}` | **抢单**（核心防超抢） | **必须 Redis SETNX 锁定** |
| `POST /api/rider/{deliveryId}/accept` | 接受派单（智能派单收到后） | 状态机原子 update |
| `POST /api/rider/{deliveryId}/reject` | 拒单（一单只能拒一次）| 转 GRABBING |
| `POST /api/rider/{deliveryId}/pickup` | **扫描取货码** → IN_TRANSIT | 校验 pickup_code |
| `POST /api/rider/{deliveryId}/delivered` | **扫描签收码** → DELIVERED | 校验 deliver_code |
| `POST /api/rider/{deliveryId}/exception` | 上报异常（客户不在/损坏/拒收）| 写 sc_delivery_exception |
| `GET /api/rider/today/income` | 今日收入和单数 | - |
| `POST /api/rider/offline` | 下线 | 必须无在途单才允许 |

### 5.2 后台管理端接口（B端）

| 接口 | 用途 |
|------|------|
| `POST /api/delivery/create` | 履约 OUTBOUND 自动调用，创建配送单 |
| `POST /api/delivery/{id}/manual-assign?riderId=` | 手动指定骑手 |
| `POST /api/delivery/{id}/reassign` | 强制改派（取消当前骑手，重派或入抢单池）|
| `POST /api/delivery/{id}/cancel` | 取消配送（业务取消订单时联动）|
| `GET /api/delivery/{id}/track` | 查骑手实时位置 + 历史轨迹 |
| `GET /api/delivery/list` | 多条件查询（status/rider/date/warehouse）|
| `GET /api/delivery/exception/list` | 异常工单列表 |
| `POST /api/delivery/exception/{id}/handle` | 处理异常工单 |

### 5.3 客户查询接口（小程序前端）

| 接口 | 用途 |
|------|------|
| `GET /api/customer/order/{orderNo}/delivery` | 用户在订单页"查看骑手"——返回脱敏骑手姓+手机+实时位置 |

---

## 6. 派单算法（核心逻辑）

### V1 简单版（Phase 2 落地版）

```
派单评分 = 距离权重 * 距离分 + 评分权重 * 骑手评分 + 负载权重 * 空闲度

距离分 = 100 * (1 - 距离 / 派单半径)
负载分 = 100 * (1 - 当前负载 / 最大并行)

候选骑手 = SELECT * FROM sc_rider
  WHERE warehouse_id = ?  AND online=1 AND status='IDLE'
        AND current_load < max_parallel
        AND ST_Distance_Sphere(...) < 派单半径
取 score 最高的 1 个 → 设为 ASSIGNED → 推送通知 → 骑手 30s 内接单/拒单
```

### V2 进阶（Phase 3）

- **顺路单合并**：同一骑手已在配送 A 单，B 单收件地址离 A 不远 → 合并派给同骑手
- **品类约束**：生鲜/冷链优先派带保温箱的骑手
- **历史时段优化**：高峰前 5 分钟提前预派单
- **多目标优化**：客户准时率 + 骑手收入均衡 + 平台成本

### 抢单防超抢（必须）

```lua
-- KEYS[1] = sc:delivery:grab:lock:{deliveryId}
-- ARGV[1] = riderId    ARGV[2] = expireSec
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end  -- 已被抢
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 1
```

抢到锁后：原子 update DB
```sql
UPDATE sc_delivery_order SET rider_id=?, status='ASSIGNED', assign_time=NOW()
WHERE id=? AND status='GRABBING'
```
affected==0 说明 DB 已被其他线程改走（冲突），**回滚 Redis 锁**（DEL 抢单 key）让骑手看到"手慢了"。

---

## 7. 通信选型：骑手 App ↔ 后端

| 场景 | 推荐 | 理由 |
|------|-----|------|
| 心跳/位置上报（骑手→服务）| HTTPS POST 30s 一次 | 简单、可控、电池友好 |
| 抢单广播（服务→骑手）| **WebSocket** 长连接 + 备用 FCM/极光推送 | 抢单要求低延迟；备用推送防 App 杀后台 |
| 改派/取消通知 | WebSocket | 同上 |
| 异常告警 | 推送 + 短信 | 双重保障 |

**WebSocket 连接管理：**
- 后端用 `Map<riderId, WebSocketSession>` 维护
- Spring Boot 用 `@ServerEndpoint` 或 `WebSocketHandler`
- 多实例部署需要 Redis Pub/Sub 转发跨实例消息

---

## 8. 与履约链路的集成（Kafka 事件）

### 新增 Kafka Topic

| Topic | 生产方 | 消费方 | 用途 |
|-------|--------|--------|------|
| `sc.delivery.create` | FulfillmentService.outbound | DeliveryService | OUTBOUND→自动建配送单 |
| `sc.delivery.delivered` | DeliveryService.delivered | FulfillmentService | DELIVERED→回写履约状态 |
| `sc.delivery.returned` | DeliveryService.returned | FulfillmentService + InventoryService | 客户拒收→触发逆向库存恢复 |
| `sc.delivery.assigned` | DeliveryService.assign | NotifyService（短信/推送）| 通知客户"骑手已接单"|

### 改造 `FulfillmentServiceImpl.outbound()`

```java
public void outbound(String orderNo) {
    // ... 现有逻辑 ...
    inventoryService.confirmDeduct(...);
    order.setStatus(OUTBOUND);
    orderMapper.updateById(order);

    // 新增：发布配送事件（用 AFTER_COMMIT 确保事务安全，配合 B6 修复）
    eventPublisher.publishEvent(new OutboundEvent(orderNo, ...));
}

@TransactionalEventListener(phase = AFTER_COMMIT)
public void onOutbound(OutboundEvent ev) {
    kafkaTemplate.send(TOPIC_DELIVERY_CREATE, ev.toMessage());
}
```

---

## 9. 并发与一致性（吸收主项目教训）

> 复用 P0 修复的设计经验，避免再踩同类坑：

| 风险点 | 借鉴的 P0 经验 | 措施 |
|-------|--------------|------|
| 骑手抢单超抢 | B7 SETNX | Lua SETNX 锁 + 条件 update 双保险 |
| 改派与骑手接单竞态 | B3 cancelIfCancelable | 条件 update：`WHERE id=? AND status='ASSIGNED' AND rider_id=?` |
| Kafka 配送事件重复消费 | B1 幂等 | 用 `(deliveryId, eventType)` 做幂等键 |
| 骑手 current_load 累加并发 | B5 INCRBY | Redis 计数 + DB 乐观锁版本号 |
| 多实例部署的 WebSocket | B13 ShedLock | Redis Pub/Sub 跨实例转发 |
| 抢单广播给骑手 App | - | 用 Redis Pub/Sub 单点广播，骑手订阅自己仓库 channel |

---

## 10. 计费 & 结算

### 配送费公式

```
基础费 = 5 元（< 2km）
距离费 = max(0, (距离km - 2)) * 1.5 元
高峰补贴 = 在 11:00-13:00 / 17:30-19:30 内 +2 元
天气补贴 = 雨天 +1 元（接对接气象 API）
超时罚款 = 超时分钟数 * 0.5 元（封顶 5 元）

骑手所得 = 基础费 + 距离费 + 高峰补贴 + 天气补贴 - 超时罚款
```

### 结算节点
- 实时入账 `sc_rider_income.settle_status=PENDING`
- 每天 23:00 跑 `RiderSettlementJob` → 当日订单全部 SETTLED → 推送骑手 App 当日账单

---

## 11. 监控指标（必须盯）

| 指标 | 阈值告警 |
|------|---------|
| 平均派单时间 | > 30 秒告警 |
| 抢单池滞留率（60秒未抢比例）| > 20% 告警（骑手不足） |
| 骑手在线数 / 仓库覆盖率 | < 阈值告警（运力预警）|
| 取货超时率 | > 5% 告警 |
| 配送准时率 | < 90% 告警 |
| 客户拒收率 | > 1% 告警（商品质量问题）|
| 骑手当日单数 Top10 | 监控刷单/作弊 |

---

## 12. 落地节奏建议（优先级）

| 阶段 | 内容 | 目标 |
|------|------|------|
| **Phase 2.1（先做）** | 表 DDL + Rider/Delivery/GrabPool 实体 + 智能派单 V1 + WebSocket 心跳 + 取货/签收码核对 | 单仓自营骑手能跑通完整流程 |
| **Phase 2.2** | 抢单池广播 + 拒单转抢单 + 异常工单 + 骑手收入结算 | 多仓+众包骑手 |
| **Phase 2.3** | 顺路单合并 + 高峰补贴/天气补贴 + 监控大盘 | 平台运营 |
| **Phase 3** | AI 派单（强化学习）+ 骑手画像 + 客户预估送达准确率优化 | 平台精细化 |

---

## 13. 与现有外壳的差异（落地清单）

| 现有外壳 | 落地后 |
|---------|--------|
| `Rider`：5 字段 | 14+ 字段（含位置/负载/评分/在线状态） |
| `DeliveryOrder`：8 字段 | 25+ 字段（含地址/距离/超时/取货码/计费） |
| `DeliveryStatus` 枚举 6 个 | 10 个（增 GRABBING/REJECTED/REASSIGNED/RETURNED/TIMEOUT） |
| `DeliveryController` 4 接口 | 拆为 RiderController（C端 11 个）+ DeliveryController（B端 8 个）+ CustomerController（1 个）|
| 无 Service | `RiderService` / `DeliveryService` / `DispatchService`（派单算法）/ `GrabPoolService` / `RiderIncomeService` |
| 无 Job | `DispatchJob`（每秒扫 PENDING 派单）/ `TimeoutJob`（每分钟扫超时）/ `OfflineCheckJob`（5min 心跳超时下线）/ `SettlementJob`（每天23点结算）|
| 无 Kafka | 新增 4 个 Topic（见第 8 节）|
| 无 WebSocket | 新增 `RiderWebSocketHandler` |
| 无 Redis Key | 新增：`sc:rider:online:{warehouseId}`(Set), `sc:rider:geo:{warehouseId}`(GEO), `sc:delivery:grab:{deliveryId}`(SET NX), `sc:rider:load:{riderId}`(String) |

---

## 📎 关联文档

- 主项目计划：`plan-supplyChain.prompt.md`
- P0 修复经验（必看，本设计大量复用）：`P0-Bug修复记录.md`
- Bug 危害时序：`Bug不修复的危害与时序图.md`

