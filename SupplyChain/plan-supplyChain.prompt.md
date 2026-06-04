# 旺生活 O2O 供应链中台 SupplyChain 模块

基于碧桂园旺生活真实参与经历重建供应链中台。以 Spring Boot 2.6 + MyBatis-Plus + **MySQL 8.0** + Redis + Kafka 技术栈，分**商品中心、多级SKU、分布式库存、自动补货、批次FIFO、生鲜效期预警**六大核心域实现，通过 Redis 原子操作保证并发安全，MQ 解耦履约事件，定时任务驱动主动预警。骑手配送与金蝶财务对接在本期以接口外壳形式预留，下期填充。

---

## 背景说明

旺生活 O2O 后台 = 社区零售履约中台 + 轻量级供应链中台

系统定位：
- 不是纯财务（金蝶）
- 不是纯大仓（SAP）
- 是面向社区小店、O2O、线上订单的「业务供应链中台」

### 系统全景（三端打通）

```
凤凰会（壳/小程序） + 旺生活（交易/履约） + 金蝶（供应链/财务）
```

**下单流程：**
业主在凤凰会/旺生活下单 → 微信/支付宝支付 → 钱先到旺生活支付账户（或碧桂园统一支付户）
→ 旺生活O2O后台记录：订单金额、应收、已收（业务流水）

**小店/供应商结算：**
小店送货完成 → 旺生活O2O算：小店应收（货款 - 佣金）
大仓/供应商：旺生活O2O把采购/调拨数据同步金蝶 → 金蝶记应付账款

**金蝶云财务核心职责：**
- 从旺生活O2O同步订单、出库、入库、结算数据
- 自动生成：销售凭证、成本凭证、应收/应付凭证、总账
- 成本核算、报税、报表、集团财务合并

---

## 技术栈

| 层次 | 技术选型 | 选型说明 |
|------|---------|---------|
| 框架 | Spring Boot 2.6.13 | - |
| ORM | MyBatis-Plus 3.5.3.1 | 条件构造器 + 自动分页 |
| 数据库 | **MySQL 8.0**（mysql-connector-j 8.0.33） | 社区小店轻量场景，支持 JSON 列、窗口函数 |
| 缓存 | Redis 7（Lettuce 连接池）| 库存预扣原子操作；⚠️ Redisson 已注释（见Bug#6）|
| 消息队列 | Kafka 3.6（KRaft模式，无ZooKeeper）| 库存/补货/预警异步解耦 |
| 连接池 | Druid 1.2.20 | SQL 监控 + 慢查询告警 |
| 工具 | Lombok、Hutool、Fastjson2、commons-pool2 | - |
| API文档 | **knife4j-openapi3-spring-boot-starter:4.4.0**（基于SpringDoc）| ⚠️ 原计划 Knife4j 3.0.3 因 Spring Boot 2.6.x NPE 已升级 |
| 监控 | Spring Boot Actuator | 健康检查 /actuator/health |
| 定时任务 | Spring @Scheduled | 生产可升级 XXL-Job |
| HTTP Client | OkHttp 4.10.0 | 金蝶云接口外壳预留 |

---

## 实现步骤

### Step 1 — 升级 pom.xml

参考 CounponSys 补全依赖：Spring Boot 2.6.13、MyBatis-Plus、**MySQL 8.0 Driver**（替换 Oracle ojdbc8）、Redis、Redisson、Kafka、Lombok、Hutool、Knife4j、Actuator、OkHttp（金蝶外壳预留）。独立 Spring Boot parent（不继承根 pom 的旧版本）。

### Step 2 — 建包结构 + 启动类

在 `com.sc.supplychain` 下创建：

```
com.sc.supplychain
├── config/         # Redis / Kafka / MyBatis / Knife4j 配置
├── controller/     # REST API 入口
├── dto/            # 请求/响应 DTO
├── entity/         # DB 实体（MyBatis-Plus）
├── enums/          # 状态枚举
├── exception/      # 自定义异常 + 全局异常处理
├── job/            # 定时任务（Scheduled）
├── listener/       # Kafka Consumer
├── mapper/         # MyBatis-Plus Mapper
├── mq/             # Kafka Producer
├── service/        # 业务逻辑层
│   └── impl/
└── util/           # 工具类
```

入口：`SupplyChainApplication.java` + `application.yml`（本地 dev profile）

### Step 3 — 商品中心（SPU / SKU 多级）

**实体设计：**
- `SpuEntity` — 标准商品单元（品类、品牌、商品名、详情、上下架状态、生鲜标志）
- `SkuEntity` — 库存单位（规格名、价格、重量、图片、关联 SPU、是否启用）
- `SkuAttrEntity` — SKU 属性（颜色/规格/重量/产地等多级属性，JSON 或行存）
- `SpuCategoryEntity` — 商品分类树（支持多级，parentId 自关联）

**枚举：**
- `ProductStatus { ON_SALE, OFF_SALE, DRAFT }`
- `FreshType { NORMAL, FRESH, FROZEN, CHILLED }`

**接口：**
- `POST /api/product/spu` — 新增SPU（含多级分类、生鲜标志）
- `POST /api/product/sku` — 新增SKU（关联SPU，含属性JSON）
- `PUT /api/product/spu/{id}/status` — 上下架（状态机保护）
- `GET /api/product/spu/list` — 分页查询（MyBatis-Plus 条件构造器）
- `GET /api/product/sku/{spuId}` — 查询SPU下所有SKU+属性

**核心实现：**
- Caffeine 本地缓存 + Redis 二级缓存商品详情（TTL 30min）
- 上架前置校验：SKU 至少一个、库存不为零、图片不为空

---

### Step 4 — 分布式库存（Redis 预扣 + MQ 异步落库 + 定时对账）

**✅ 选定一致性策略：预扣 + MQ异步落库 + 定时对账**

两种方案对比：

| 策略 | 性能 | 一致性 | 适用场景 |
|------|------|--------|---------|
| **预扣 + MQ异步落库 + 定时对账（选定）** | ✅ 高（微秒级Redis操作） | 最终一致 | 高并发 O2O 下单，旺生活真实场景 |
| 同步双写（Redis + DB 同一事务）| ❌ 低（DB 锁等待） | 强一致 | 低并发、金额强对账场景（不适合） |

**选定方案的三道防线：**
1. **预扣**：Redis `DECRBY` 原子扣减，用 Lua Script 保证检查+扣减原子性，防超卖
2. **异步落库**：Kafka `sc.inventory.deduct` → `InventoryListener` 消费写 DB，Kafka Offset 手动提交，消费失败重试3次后进死信队列（DLQ）人工介入
3. **定时对账**：`@Scheduled(cron = "0 0 2 * * ?")` 每天凌晨2点全量比对 Redis `available` 与 DB `available_qty`，差异超阈值则告警 + 自动修复（以 DB 为准）

**Redis 库存 Lua Script（防超卖核心）：**
```lua
-- KEYS[1] = inventory:available:{warehouseId}:{skuId}
-- ARGV[1] = 扣减数量
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock < tonumber(ARGV[1]) then
    return -1   -- 库存不足
end
return redis.call('DECRBY', KEYS[1], ARGV[1])
```

**实体设计：**
- `InventoryEntity` — 仓库级可用库存（skuId、warehouseId、availableQty、lockedQty、totalQty）
- `InventoryBatchEntity` — 批次（batchNo、skuId、warehouseId、inboundQty、remainQty、productDate、expireDate、inboundTime）
- `InventoryLogEntity` — 库存流水（操作类型、前值、后值、来源单号）

**Redis 键设计：**
```
inventory:available:{warehouseId}:{skuId}   → String（可用数量，预扣主键）
inventory:locked:{warehouseId}:{skuId}      → String（锁定数量，辅助统计）
inventory:batch:{warehouseId}:{skuId}       → ZSet（score=inboundTime Unix毫秒，即 FIFO）
inventory:reconcile:diff                    → Set（对账差异 skuId 集合，供告警）
expiry:warn:set                             → Set（临期批次 batchId 集合）
```

**核心实现（InventoryService）：**
- `lockStock(warehouseId, skuId, qty)` — Lua Script 原子预扣 Redis → Kafka `sc.inventory.deduct`（异步落库）→ 返回成功/库存不足
- `unlockStock(warehouseId, skuId, qty)` — 释放预扣（订单取消/超时）→ Redis INCRBY → Kafka `sc.inventory.restore`
- `confirmDeduct(warehouseId, skuId, qty, batchAllocations)` — 出库确认，Kafka `sc.inventory.confirm`，DB lockedQty - qty，totalQty - qty
- `inbound(batchDTO)` — 入库：DB 写 batch + DB availableQty += qty → Redis INCRBY，ZSet `ZADD` 加批次（score=inboundTime）
- `allocateFifo(warehouseId, skuId, reqQty)` — ZSet `ZRANGEBYSCORE` 按 score 升序（最早入库先出），跨批次累计分配，返回 `List<BatchAllocation>`
- `reconcile()` — 对账核心：`SCAN` Redis keys → 逐一对比 DB，差异写 `inventory:reconcile:diff`

---

### Step 5 — 自动补货（定时任务 + Kafka）

**实体设计：**
- `ReplenishmentRuleEntity` — 规则（skuId、warehouseId、minQty 最小库存、replenishQty 补货量、supplierId、isEnabled）
- `ReplenishmentOrderEntity` — 补货单（ruleId、skuId、qty、status、triggerTime、confirmTime）

**定时任务（ReplenishmentCheckJob）：**
```
@Scheduled(cron = "0 */30 * * * ?")   // 每30分钟扫描
扫描所有启用规则 → 查 Redis available < minQty → 生成补货单 → 发 Kafka replenishment.trigger
```

**Kafka Topic：**
- `replenishment.trigger` → `ReplenishmentListener` → 发 HTTP 通知供应商 / 写补货单

**接口：**
- `POST /api/replenishment/rule` — 新增补货规则
- `GET /api/replenishment/orders` — 查补货单列表
- `PUT /api/replenishment/order/{id}/confirm` — 手动确认补货完成 → 触发入库

---

### Step 6 — 批次 FIFO + 生鲜效期预警

**批次 FIFO 出库规则：**
- `InventoryBatchService.allocateFifo(skuId, warehouseId, reqQty)` — 按 `inboundTime ASC` 顺序出批次，单批不够则顺延下一批，返回 `List<BatchAllocation>`
- 每次出库写 `InventoryLogEntity` 关联 batchNo

**效期预警定时任务（ExpiryWarningJob）：**
```
@Scheduled(cron = "0 0 8 * * ?")    // 每天早8点
扫描 inventory_batch 中 expire_date 在今天+预警天数内 且 remainQty > 0
→ 发 Kafka expiry.warning
→ 写预警记录 ExpiryWarningRecordEntity
→ 推送微信消息/短信（预留接口）
```

**效期分级：**
```
NORMAL     → expire_date - today > 7天
NEAR_EXPIRY→ 3 ≤ expire_date - today ≤ 7天（黄色预警）
URGENT     → expire_date - today < 3天（红色预警，需人工处理）
EXPIRED    → expire_date < today（下架 + 记录报损）
```

**接口：**
- `GET /api/batch/list?skuId=&warehouseId=` — 查批次列表（含剩余量、状态）
- `GET /api/expiry/warning/list` — 查临期预警列表
- `PUT /api/batch/{batchId}/writeoff` — 报损处理（库存清零 + 凭证）

---

### Step 7 — O2O 履约事件流

**流程：**
```
前端/小程序下单
  → POST /api/fulfillment/order/create
  → 校验商品可售状态
  → Redis 预扣库存（lockStock）
  → DB 写 FulfillmentOrderEntity（PENDING）
  → Kafka inventory.deduct
      → InventoryListener 异步落库 lockedQty+=qty, availableQty-=qty
  → 支付回调 → DB 更新状态 PAID
  → 拣货出库 → allocateFifo → confirmDeduct
      → Kafka inventory.confirm
      → InventoryListener DB lockedQty-=qty, totalQty-=qty
  → 配送（预留骑手接口）
  → 完成

订单取消/超时
  → Kafka inventory.restore
  → InventoryListener Redis INCRBY + DB lockedQty-=qty, availableQty+=qty
```

**实体：**
- `FulfillmentOrderEntity` — 订单（orderId、storeId、skuId、qty、status、payAmount、payTime、addressId）
- `FulfillmentRecordEntity` — 流水（操作类型、操作人、时间、备注）
- `StoreEntity` — 社区小店（storeId、storeName、communityId、address、riderIds）

**接口：**
- `POST /api/fulfillment/order/create`
- `POST /api/fulfillment/order/{orderId}/cancel`
- `POST /api/fulfillment/order/{orderId}/outbound` — 出库确认
- `GET /api/fulfillment/order/list?storeId=`

---

## 数据库 DDL（MySQL 8.0）

> **选型说明：** 改用 MySQL 8.0，社区小店轻量场景，运维/开发成本更低，支持 JSON 列存 SKU 属性，MyBatis-Plus 无缝兼容。ID 统一使用 BIGINT（MyBatis-Plus `@TableId(type = IdType.ASSIGN_ID)` 雪花算法）。

```sql
-- ============================================================
-- 初始化脚本：sc_supply_chain.sql
-- 执行前请先创建数据库：CREATE DATABASE sc_supply_chain DEFAULT CHARSET utf8mb4;
-- ============================================================

-- 商品分类表（多级树）
CREATE TABLE sc_category (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '分类ID（雪花）',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID，0=顶级',
    cat_name    VARCHAR(100) NOT NULL COMMENT '分类名',
    level       TINYINT      NOT NULL DEFAULT 1 COMMENT '层级(1/2/3)',
    sort        INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent(parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

-- SPU 商品表
CREATE TABLE sc_spu (
    id          BIGINT        NOT NULL PRIMARY KEY,
    spu_name    VARCHAR(200)  NOT NULL COMMENT '商品名称',
    category_id BIGINT        COMMENT '分类ID',
    brand       VARCHAR(100),
    fresh_type  VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/FRESH/FROZEN/CHILLED',
    status      VARCHAR(20)   NOT NULL DEFAULT 'DRAFT'  COMMENT 'DRAFT/ON_SALE/OFF_SALE',
    description TEXT,
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category(category_id),
    INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准商品单元';

-- SKU 表（含 JSON 多级属性）
CREATE TABLE sc_sku (
    id          BIGINT        NOT NULL PRIMARY KEY,
    spu_id      BIGINT        NOT NULL COMMENT '关联SPU',
    sku_name    VARCHAR(200),
    sku_attrs   JSON          COMMENT '[{"attrKey":"规格","attrVal":"500g"},...]',
    price       DECIMAL(12,2) COMMENT '售价',
    weight      DECIMAL(10,3) COMMENT '克重',
    img_url     VARCHAR(500),
    status      VARCHAR(20)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_spu(spu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存单位';

-- 仓库表
CREATE TABLE sc_warehouse (
    id             BIGINT       NOT NULL PRIMARY KEY,
    warehouse_name VARCHAR(100) NOT NULL,
    address        VARCHAR(300),
    type           VARCHAR(20)  NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN=大仓/STORE=小店',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

-- 库存表（仓库级）
CREATE TABLE sc_inventory (
    id             BIGINT   NOT NULL PRIMARY KEY,
    sku_id         BIGINT   NOT NULL,
    warehouse_id   BIGINT   NOT NULL,
    available_qty  INT      NOT NULL DEFAULT 0 COMMENT '可用库存（Redis镜像）',
    locked_qty     INT      NOT NULL DEFAULT 0 COMMENT '锁定库存',
    total_qty      INT      NOT NULL DEFAULT 0 COMMENT '总库存',
    update_time    DATETIME ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_wh(sku_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库级库存';

-- 库存批次表（FIFO）
CREATE TABLE sc_inventory_batch (
    id           BIGINT      NOT NULL PRIMARY KEY,
    batch_no     VARCHAR(64) NOT NULL UNIQUE COMMENT '批次号',
    sku_id       BIGINT      NOT NULL,
    warehouse_id BIGINT      NOT NULL,
    inbound_qty  INT         NOT NULL COMMENT '入库数量',
    remain_qty   INT         NOT NULL COMMENT '剩余数量',
    product_date DATE        COMMENT '生产日期',
    expire_date  DATE        COMMENT '过期日期',
    inbound_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'FIFO排序依据',
    status       VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/NEAR_EXPIRY/URGENT/EXPIRED/WRITTEN_OFF',
    INDEX idx_sku_wh_expire(sku_id, warehouse_id, expire_date),
    INDEX idx_inbound_time(inbound_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存批次（FIFO/效期）';

-- 库存流水
CREATE TABLE sc_inventory_log (
    id           BIGINT      NOT NULL PRIMARY KEY,
    sku_id       BIGINT,
    warehouse_id BIGINT,
    batch_no     VARCHAR(64),
    op_type      VARCHAR(30) NOT NULL COMMENT 'INBOUND/LOCK/UNLOCK/CONFIRM/WRITEOFF',
    qty_before   INT,
    qty_after    INT,
    delta_qty    INT,
    ref_no       VARCHAR(64) COMMENT '关联单号（订单号/补货单）',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sku(sku_id),
    INDEX idx_ref(ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存操作流水';

-- 补货规则
CREATE TABLE sc_replenishment_rule (
    id             BIGINT   NOT NULL PRIMARY KEY,
    sku_id         BIGINT   NOT NULL,
    warehouse_id   BIGINT   NOT NULL,
    min_qty        INT      NOT NULL COMMENT '触发阈值',
    replenish_qty  INT      NOT NULL COMMENT '每次补货量',
    supplier_id    BIGINT   COMMENT '供应商ID',
    is_enabled     TINYINT  NOT NULL DEFAULT 1,
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_wh(sku_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动补货规则';

-- 补货单
CREATE TABLE sc_replenishment_order (
    id             BIGINT      NOT NULL PRIMARY KEY,
    rule_id        BIGINT,
    sku_id         BIGINT,
    warehouse_id   BIGINT,
    qty            INT,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/INBOUND/CANCELLED',
    trigger_time   DATETIME,
    confirm_time   DATETIME,
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货单';

-- 社区小店
CREATE TABLE sc_store (
    id           BIGINT       NOT NULL PRIMARY KEY,
    store_name   VARCHAR(200) NOT NULL,
    community_id BIGINT       COMMENT '社区ID',
    address      VARCHAR(300),
    phone        VARCHAR(20),
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区小店';

-- O2O 履约订单
CREATE TABLE sc_fulfillment_order (
    id           BIGINT        NOT NULL PRIMARY KEY,
    order_no     VARCHAR(64)   NOT NULL UNIQUE,
    store_id     BIGINT,
    sku_id       BIGINT,
    qty          INT,
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                               COMMENT 'PENDING/PAID/PICKING/OUTBOUND/DELIVERING/DELIVERED/CANCELLED',
    pay_amount   DECIMAL(12,2),
    pay_time     DATETIME,
    address      VARCHAR(500),
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_store(store_id),
    INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='O2O履约订单';

-- 履约流水
CREATE TABLE sc_fulfillment_record (
    id         BIGINT       NOT NULL PRIMARY KEY,
    order_id   BIGINT       NOT NULL,
    op_type    VARCHAR(30)  NOT NULL COMMENT 'CREATE/PAY/PICK/OUTBOUND/DELIVER/CANCEL',
    op_by      VARCHAR(50)  COMMENT '操作人',
    remark     VARCHAR(300),
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约操作流水';

-- 效期预警记录
CREATE TABLE sc_expiry_warning (
    id          BIGINT      NOT NULL PRIMARY KEY,
    batch_no    VARCHAR(64),
    sku_id      BIGINT,
    warn_level  VARCHAR(20) NOT NULL COMMENT 'NEAR_EXPIRY/URGENT',
    expire_date DATE,
    remain_qty  INT,
    is_handled  TINYINT     NOT NULL DEFAULT 0,
    warn_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sku(sku_id),
    INDEX idx_handled(is_handled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='效期预警记录';

-- ============================================================
-- Phase 2 预留表（本期建表，业务逻辑下期实现）
-- ============================================================

-- 骑手表（预留）
CREATE TABLE sc_rider (
    id          BIGINT      NOT NULL PRIMARY KEY,
    rider_name  VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)  NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/BUSY/OFFLINE',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手[Phase2预留]';

-- 配送单（预留）
CREATE TABLE sc_delivery_order (
    id                 BIGINT      NOT NULL PRIMARY KEY,
    fulfillment_order_id BIGINT    NOT NULL COMMENT '关联履约订单',
    rider_id           BIGINT      COMMENT '分配骑手',
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                   COMMENT 'PENDING/ASSIGNED/PICKING/IN_TRANSIT/DELIVERED/FAILED',
    assign_time        DATETIME,
    pickup_time        DATETIME,
    delivered_time     DATETIME,
    fail_reason        VARCHAR(200),
    create_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order(fulfillment_order_id),
    INDEX idx_rider(rider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送单[Phase2预留]';

-- 金蝶同步记录（预留）
CREATE TABLE sc_kingdee_sync (
    id          BIGINT      NOT NULL PRIMARY KEY,
    sync_type   VARCHAR(30) NOT NULL COMMENT 'SALES_VOUCHER/COST_VOUCHER/AP_VOUCHER',
    ref_no      VARCHAR(64) COMMENT '来源单号',
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    request_body  TEXT      COMMENT '请求报文快照',
    response_body TEXT      COMMENT '响应报文快照',
    fail_reason   VARCHAR(500),
    retry_count   INT        NOT NULL DEFAULT 0,
    sync_time     DATETIME,
    create_time   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ref(ref_no),
    INDEX idx_status(sync_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='金蝶云同步记录[Phase2预留]';
```

---

## Kafka Topic 设计

| Topic | 生产方 | 消费方 | 说明 |
|-------|--------|--------|------|
| `sc.inventory.deduct` | FulfillmentService | InventoryListener | 订单预扣后异步DB落库 |
| `sc.inventory.restore` | FulfillmentService | InventoryListener | 取消/超时释放库存 |
| `sc.inventory.confirm` | FulfillmentService | InventoryListener | 出库确认 |
| `sc.replenishment.trigger` | ReplenishmentCheckJob | ReplenishmentListener | 触发补货单生成 |
| `sc.expiry.warning` | ExpiryWarningJob | ExpiryWarningListener | 临期预警推送 |

---

## 后期扩展预留（Phase 2）

> **本期策略：建表 + 接口外壳（空实现 + TODO 注释），下期填充业务逻辑。**

### 骑手配送（Phase 2 外壳预留）

**本期包含：**
- `sc_rider` / `sc_delivery_order` 建表（见DDL）
- `RiderEntity` / `DeliveryOrderEntity` — 实体类（含状态机枚举）
- `DeliveryController` — 接口外壳，方法体仅 `// TODO Phase2` + 返回占位
- `DeliveryStatusEnum { PENDING, ASSIGNED, PICKING, IN_TRANSIT, DELIVERED, FAILED }`

**状态机流转图（下期实现）：**
```
PENDING → ASSIGNED（派单）→ PICKING（取货中）→ IN_TRANSIT（配送中）→ DELIVERED（完成）
                                                                    ↘ FAILED（失败，可重新派单）
```

**下期实现：**
- 骑手 App WebSocket 实时位置上报（`DeliveryTrackingService`）
- 自动派单算法（距离+负载最优）
- 超时未取货 → 自动重新派单

### 金蝶云对接（Phase 2 外壳预留）

**本期包含：**
- `sc_kingdee_sync` 建表（见DDL）
- `KingdeeSyncEntity` — 同步记录实体
- `KingdeeApiClient` — HTTP Client 外壳（OkHttp），方法体仅 `// TODO Phase2`
- `KingdeeDataSyncJob` — 定时任务外壳，`@Scheduled(cron = "0 0 1 * * ?")` 凌晨1点

**同步数据映射（下期实现）：**
```
旺生活出库单  → 金蝶销售凭证（借：应收账款  贷：主营业务收入）
旺生活采购入库 → 金蝶应付凭证（借：库存商品  贷：应付账款）
旺生活出库成本 → 金蝶成本凭证（借：主营业务成本  贷：库存商品）
```

**接口外壳（下期填充）：**
- `POST /api/kingdee/sync/manual` — 手动触发同步（管理员用）
- `GET /api/kingdee/sync/records` — 查同步记录 + 失败原因

---

## 进一步思考（已决策）

1. ✅ **数据库选型** — **MySQL 8.0**（替换 Oracle），社区小店轻量场景，运维成本低，SKU 属性用原生 JSON 列，MyBatis-Plus 零改造。DDL 脚本见上方，均为 MySQL 语法。

2. ✅ **Redis 库存一致性策略** — 采用**预扣 + MQ异步落库 + 定时对账**三道防线：
   - Lua Script 原子预扣（防超卖）
   - Kafka 异步落库（高性能，失败重试+DLQ）
   - 每天凌晨2点全量对账（以DB为准修复Redis漂移）

3. ✅ **后期扩展预留** — 骑手配送 + 金蝶云对接在**本期建表 + 创建接口外壳**，方法体 `// TODO Phase2`，保证项目结构完整、下期可直接填充，不影响本期功能。

4. **分布式锁粒度** — 锁 key 为 `inventory:lock:{warehouseId}:{skuId}`，粒度到 SKU+仓库，避免全局锁；Lua Script 比 Redisson 延迟更低，优先用 Lua，超复杂事务才用 Redisson。⚠️ **实际状态**：Redisson 依赖已注释（无密码 Redis 会触发 AUTH 错误），Phase 2 实现时需先配置 Redis 密码再取消注释。

5. **生鲜预警天数配置化** — 预警天数（3/7天）通过 `application.yml` `supply-chain.expiry.warn-days-near=7` / `warn-days-urgent=3` 配置，`@ConfigurationProperties` 注入，不同品类阈值后期升级为 DB 配置表。

6. **MySQL 连接池配置** — Druid + `spring.datasource.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/sc_supply_chain?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false`，`testWhileIdle=true` 防连接断失。⚠️ **注意**：`characterEncoding` 必须用 `UTF-8`（Java标准），不能用 `utf8mb4`（MySQL charset名），否则 JDBC 报错。Docker 容器内 `DB_PORT` 应为 `3306`（容器内部端口），宿主机访问用 `3307`。



## 自己写的，记录
✅ 执行完成 — 旺生活 O2O 供应链中台
文件统计：74 个 Java 文件 + 3 个资源文件 + 1 个 pom.xml

---

## 实际落地状态（v1.2 | 2026-06-01）

### ✅ 已完成清单

| # | 内容 | 状态 |
|---|------|------|
| 1 | 74个Java文件 + application.yml + init.sql + inventory_lock.lua | ✅ 生成完毕 |
| 2 | MySQL 8.0 + Redis + Kafka Docker 容器启动 | ✅ 运行中 |
| 3 | Spring Boot 应用打包进 Docker（sc-app 容器）| ✅ 运行中（端口 8091）|
| 4 | 9 个 Bug 修复（见下方 Bug 记录）| ✅ 全部修复 |
| 5 | Knife4j 3.x → 4.4.0（基于 SpringDoc OpenAPI 3）| ✅ 升级完成 |
| 6 | Controller 注解迁移（Swagger2 → OpenAPI3）| ✅ 完成 |
| 7 | 日志落盘配置（app.log 自动写文件）| ✅ 完成 |
| 8 | 完整业务流程测试（建SPU→建SKU→上架→入库→下单→支付→出库）| ✅ 验证通过 |

### ⏳ 未完成清单（TODO）

| # | 优先级 | 内容 | 说明 |
|---|--------|------|------|
| 1 | 🔴高 | 超卖并发演示 | 100并发下单只有10件库存，验证Lua防超卖 |
| 2 | 🟡中 | 骑手配送模块业务实现 | DeliveryController外壳已有，逻辑待填充 |
| 3 | 🟡中 | 金蝶云财务对接实现 | KingdeeApiClient外壳已有，HTTP逻辑待填充 |
| 4 | 🟡中 | Redisson分布式锁启用 | 补货审批等场景，pom.xml已注释，激活需配置Redis密码 |
| 5 | 🟢低 | JMeter压测HTML报告 | 图形化并发报告 |
| 6 | 🟢低 | Kubernetes部署 | k8s/目录已预留 |
| 7 | 🟢低 | CI/CD流水线 | GitHub Actions |
| 8 | 🟢低 | 生产环境配置文件 | application-prod.yml（密码加密、日志INFO）|
| 9 | 🟢低 | 单元测试 | Service层核心逻辑缺测试用例 |

---

## 实际使用的技术栈（与原计划的差异）

| 层次 | 原计划 | **实际使用** | 变更原因 |
|------|--------|------------|---------|
| API 文档 | Knife4j 3.0.3（Springfox）| **knife4j-openapi3-spring-boot-starter:4.4.0** | Springfox 3.x + Spring Boot 2.6.x NPE兼容问题 |
| Swagger 注解 | `@Api` / `@ApiOperation` | **`@Tag` / `@Operation`** | Knife4j 4.x 迁移到 OpenAPI 3 |
| Redisson | redisson-spring-boot-starter:3.17.7 | **注释掉（暂未启用）** | 无密码Redis自动发`AUTH ""`导致启动失败 |
| JDBC编码 | `characterEncoding=utf8mb4` | **`characterEncoding=UTF-8`** | utf8mb4是MySQL charset名，不是Java charset |
| Spring Boot 运行方式 | 本地 mvn spring-boot:run | **Docker容器 sc-app** | 所有服务统一Docker，环境一致 |
| Dockerfile | 多阶段Maven构建 | **单阶段（复制本地JAR）** | Docker内拉Maven依赖慢10分钟，改为本地先打包 |

---

## Docker 环境（当前运行状态）

5个Docker容器全部运行，一条命令启动：

```powershell
# 第一步：打JAR（改代码后才需要）
& "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q

# 第二步：启动全部服务
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
docker-compose --progress plain up -d --build supply-chain-app
```

| 容器名 | 镜像 | 端口 | 用途 |
|--------|------|------|------|
| `sc-app` | 本地构建 | 8091:8091 | Spring Boot 应用 |
| `sc-mysql` | mysql:8.0.33 | 3307:3306 | 主数据库 |
| `sc-redis` | redis:7-alpine | 6380:6379 | 库存缓存 |
| `sc-kafka` | confluentinc/cp-kafka:7.6.1 | 9092:9092 | 消息队列 |
| `sc-kafka-ui` | provectuslabs/kafka-ui:latest | 8080:8080 | Kafka管理界面 |

**访问地址：**
- Knife4j UI：http://localhost:8091/doc.html ← **推荐**
- Swagger UI：http://localhost:8091/swagger-ui/index.html
- Kafka UI：http://localhost:8080
- Druid监控：http://localhost:8091/druid/ (admin/admin123)

---

## Bug 修复记录（9个，全部已修复）

### Bug #1 — Redis Lua `tonumber()` 解析失败（最关键）

**现象**：入库成功，下单报"库存不足"，Redis 明明有值。

**根因**：`RedisTemplate<String,Object>` + Jackson 序列化，Redis存的是 `["java.lang.Long",100]`，Lua `tonumber()` 无法解析，返回 nil。

**修复**：改用 `StringRedisTemplate`，Redis 存纯字符串 `"100"`。

```java
// 修复前：redisTemplate.opsForValue().set(key, qty);  // ["java.lang.Long",100]
// 修复后：stringRedisTemplate.opsForValue().set(key, String.valueOf(qty)); // "100"
```

---

### Bug #2 — 缓存穿透：-2 返回值处理错误

**根因**：Lua 返回 `-2` 表示缓存 Key 不存在，但代码把 `-2` 和 `-1`（库存不足）一起处理了，未触发缓存预热重试。

**修复**：显式判断 `-2` → 从 DB 预热 Redis → 重试 Lua。

---

### Bug #3 — FulfillmentOrder 缺 warehouseId 字段

**根因**：实体类没有 `warehouseId`，取消/出库时用硬编码 `DEFAULT_WAREHOUSE_ID=1`，多仓场景出错。

**修复**：实体加字段，下单时保存，出库/取消时用 `order.getWarehouseId()`。

---

### Bug #4 — 效期预警 warn_time 为 null

**根因**：`MetaObjectFillHandler` 的 `insertFill()` 没填充 `warnTime`。

**修复**：`insertFill()` 补充 `warnTime` 自动填充。

---

### Bug #5 — JDBC 编码参数错误

**根因**：`characterEncoding=utf8mb4` 是 MySQL 字符集名，不是 Java 标准字符集名。

**修复**：改为 `characterEncoding=UTF-8`。

---

### Bug #6 — Redisson 无密码 Redis 报 AUTH 错误

**根因**：`redisson-spring-boot-starter` 自动连接 Redis 并发 `AUTH ""`，无密码 Redis 拒绝。

**修复**：注释掉该依赖（代码未实际使用 Redisson，是 Phase 2 预留）。

---

### Bug #7 — Springfox 3.0.0 + Spring Boot 2.6.x NPE

**根因**：Spring Boot 2.6.x 路径匹配改用 `PathPatternParser`，Springfox 内部写死 `AntPathMatcher`，不兼容。

**修复**：放弃 Springfox/Knife4j 3.x，直接升级到 Knife4j 4.4.0（基于 SpringDoc）。

---

### Bug #8 — application.yml 重复 `logging:` key

**根因**：修改日志配置时误写了两个 `logging:` 块，YAML 不允许同层重复 key。

**修复**：合并为一个 `logging:` 块。

---

### Bug #9 — Docker 容器内 DB 连接失败（端口默认值错误）

**根因**：`DB_PORT` 默认值写的是 `3307`（宿主机映射端口），但容器内应连 `mysql:3306`（Docker内部端口）。

**修复**：默认值改为 `3306`。

---

## 📦 模块结构

| 包/目录 | 内容 |
|---------|------|
| config/ | RedisConfig（Lua脚本注册）、KafkaConfig（7个Topic+DLT）、MybatisPlusConfig、MetaObjectFillHandler、SupplyChainProperties、SwaggerConfig（Knife4j 4.x OpenAPI Bean） |
| entity/ | 16个实体：Spu、Sku、SpuCategory、Warehouse、Inventory、InventoryBatch、InventoryLog、ReplenishmentRule、ReplenishmentOrder、Store、FulfillmentOrder（含warehouseId字段）、FulfillmentRecord、ExpiryWarning + Phase2: Rider、DeliveryOrder、KingdeeSync |
| enums/ | 7个枚举：ProductStatus、FreshType、BatchStatus（四级效期）、FulfillmentOrderStatus、InventoryOpType、ReplenishmentStatus、DeliveryStatus |
| mapper/ | 15个 Mapper（含 InventoryMapper 自定义 lockQty/unlockQty/confirmDeductQty、InventoryBatchMapper FIFO查询、ExpiryWarningMapper 临期扫描）|
| service/ | 4个服务：ProductService（上下架状态机）、InventoryService（Lua预扣+FIFO+对账）、ReplenishmentService（阈值扫描）、FulfillmentService（全链路履约）|
| controller/ | 6个接口：Product、Inventory、Replenishment、Fulfillment（含效期预警）、Delivery（Phase2外壳）、Kingdee（Phase2外壳）|
| mq/ | InventoryEventProducer（deduct/restore/confirm/replenishment）|
| listener/ | InventoryListener（DEDUCT/RESTORE/CONFIRM落库+DLT告警）、ReplenishmentListener（生成补货单）|
| job/ | ReplenishmentCheckJob（每30分钟）、ExpiryWarningJob（每天早8点四级预警）、InventoryReconcileJob（每天凌晨2点对账）、KingdeeDataSyncJob（Phase2外壳）|
| integration/ | KingdeeApiClient（Phase2外壳，3种凭证方法）|
| exception/ | SupplyChainException（stockInsufficient/notFound/illegalStatus）、GlobalExceptionHandler |
| util/ | RedisKeyUtil（所有Redis Key常量）|
| resources/ | application.yml（MySQL8/Redis/Kafka/Knife4j/供应链配置）、lua/inventory_lock.lua（防超卖Lua）、db/init.sql（全量MySQL8 DDL，16张表）|

---

## 🔑 核心技术实现

**防超卖（最重要）**：`inventory_lock.lua` Lua Script 原子检查+DECRBY，返回 `-1`（库存不足）/ `-2`（Key不存在，触发预热重试）/ `≥0`（成功）。必须用 `StringRedisTemplate` 存纯字符串，否则 `tonumber()` 无法解析。

**FIFO出库**：`InventoryBatchMapper.selectFifoBatches()` 按 `inbound_time ASC` 排序 + `allocateFifo()` 跨批次分配，返回 `List<BatchAllocation>`。

**异步落库**：预扣成功后发 Kafka `sc.inventory.deduct` → `InventoryListener` 消费更新DB，失败重试3次后进DLT死信队列人工介入。

**定时对账**：`InventoryReconcileJob` 凌晨2点全量扫描 Redis vs DB，以DB为准修复Redis漂移，写对账流水。

**效期四级**：`ExpiryWarningJob` 每日8点扫描，NORMAL/NEAR_EXPIRY(≤7天)/URGENT(≤3天)/EXPIRED 分级处理，预警天数通过 `application.yml` 配置。

**API文档**：Knife4j 4.4.0，访问 http://localhost:8091/doc.html，左侧菜单按 Tag 分组（商品中心/库存管理/O2O履约/补货管理/效期预警/数据对账）。

---

## 🐛 当前系统已知 Bug & 设计缺陷（深度审计 | 2026-06-03）

> 上面 9 个 Bug 是**启动期已修复**的硬错误。下面这些是**对现有代码做深度审计**后发现的**仍然存在**的并发/一致性/可靠性问题，绝大多数在低并发演示下不会暴露，但在生产或压测时会出问题。**全部都是真实代码逻辑问题，不是假想。**

### 🔴 Critical（必须修，影响正确性）

#### B1 — Kafka 消费者**没有幂等检查**（重复消费会导致 DB 库存重复扣减）

**位置**：`InventoryListener.onDeduct/onRestore/onConfirm`

**现象**：Kafka 是 at-least-once 语义。容器重启、ack 失败、rebalance 都会导致同一条消息被消费多次。当前消费逻辑只是直接执行 SQL 更新，无幂等键。

**后果**：一笔订单的 `lockQty` 落库执行两次 → DB `locked_qty` 被加 2 倍。直到凌晨对账才会发现 Redis vs DB 漂移。

**修复**：用 `sc_inventory_log` 表的 `(ref_no, op_type)` 加唯一索引做幂等键，消费前先查 log 是否已存在。

---

#### B2 — Redis 预扣与 DB 落库的**时序竞争**（出库失败）

**位置**：`FulfillmentServiceImpl.outbound()` → `confirmDeduct()` → `confirmDeductQty` SQL `WHERE locked_qty >= qty`

**现象**：
1. 用户下单 → `lockStock` Redis 立即扣减 + 发 Kafka `inventory.deduct` 消息（异步）
2. 用户立刻支付 + 出库（极速 demo / 慢消费场景）
3. `confirmDeduct` 执行 SQL：`UPDATE ... WHERE locked_qty >= qty`，**但 Kafka 消息还没被消费，DB locked_qty 仍是 0** → affected = 0 → 抛 `FIFO批次扣减失败`

**后果**：合法订单出库失败。

**修复**：`outbound` 时先确保 deduct 消息已落库（同步等待 / 改用同步双写 / 业务上 PAID→OUTBOUND 之间有人工拣货时差，问题被天然规避）。

---

#### B3 — `cancelOrder` **无状态原子保护**（并发取消会双倍释放库存）

**位置**：`FulfillmentServiceImpl.cancelOrder`

**现象**：当前代码先 select → 判断状态 → unlockStock → updateById。两个并发取消请求都通过状态检查，都执行 `unlockStock` → Redis +qty 两次（凭空多出库存）+ DB `unlockQty` SQL 也执行两次。

**后果**：库存超发 / DB 数据漂移。

**修复**：用条件 update：
```sql
UPDATE sc_fulfillment_order SET status='CANCELLED'
WHERE order_no=? AND status IN ('PENDING','PAID')
```
返回 `affected==0` 直接跳过释放逻辑。

---

#### B4 — `cancelOrder` 允许 **OUTBOUND 后取消**（库存错乱）

**位置**：`FulfillmentServiceImpl.cancelOrder` line 65

**现象**：当前只排除了 `DELIVERED` / `CANCELLED`，但 `PICKING / OUTBOUND` 状态都允许取消。OUTBOUND 后真实库存已经通过 `confirmDeduct` 扣掉了（`locked_qty/total_qty -= qty`），再 unlock 会让 Redis += qty（凭空多货），DB `unlockQty` 因 `locked_qty < qty` 静默失败。

**修复**：`if (!PENDING && !PAID) throw`。

---

#### B5 — `inbound` Redis 同步**非原子**（并发入库覆盖丢失）

**位置**：`InventoryServiceImpl.inbound` line 86-93

**现象**：步骤是 ① DB +qty ② **重新 select DB** ③ `set Redis = DB值`（覆盖式）。

并发场景：
- T1 入库 100 → DB=100 → set Redis=100
- T2 同时入库 50 → DB=150 → set Redis=150
- 但中间用户下单减了 10，Redis 已经是 90 → T2 的 set 把 Redis 覆盖回 150（多出 10）→ 超卖

**修复**：用 `INCRBY qty` 而不是 `SET DB值`，让 Redis 累加。

---

#### B6 — `lockStock` + `createOrder` 跨"业务+消息"**不一致**

**位置**：`FulfillmentServiceImpl.createOrder` (transactional) → `inventoryService.lockStock` 内部 Redis 扣减 + Kafka 发送

**现象**：
1. lockStock 成功（Redis 已扣减、Kafka 消息已发出）
2. 接着 `orderMapper.insert(order)` 因为某种原因失败（DB死锁、唯一键冲突等）
3. 事务回滚订单，但 **Redis 已扣 + Kafka 消息已发**
4. 结果：库存被吃掉但订单不存在

**修复**：transactional outbox 模式，或用 `TransactionalEventListener(AFTER_COMMIT)` 把 Kafka send 移到事务提交后；或反过来：发送失败时主动 INCRBY 回滚 Redis。

---

#### B7 — `warmupRedisStock` **没有锁**（多请求同时预热互相覆盖）

**位置**：`InventoryServiceImpl.warmupRedisStock`

**现象**：100 个并发请求都看到 Lua 返回 -2，都触发 `warmupRedisStock` → 都执行 `set Redis = DB值`。期间任何一个请求 Lua 扣减成功后，被后续 warmup 覆盖回原值 → 超卖。

**修复**：warmup 用 `SET NX`（只在 key 不存在时 set），或加分布式锁 `inventory:warmup:lock:{wh}:{sku}`。

---

### 🟡 High（影响生产可靠性）

#### B8 — `allocateFifo` **没有分布式锁**（并发出库批次分配冲突）

**位置**：`InventoryServiceImpl.allocateFifo`

两个并发出库都 SELECT 拿到相同批次列表，都尝试扣减同一批次的 remain_qty。`deductBatchRemain` SQL 应该有 `WHERE remain_qty >= qty` 做乐观保护，第二个失败 throw 导致整个事务回滚——但**第一个事务可能已经写了流水/部分扣减成功**，业务报错。

**修复**：`allocateFifo + deductBatchRemain` 整个流程加 Redisson 锁 `inventory:fifo:lock:{wh}:{sku}`。

---

#### B9 — `InventoryReconcileJob` 修复时**没锁**（修复期间被并发下单覆盖）

**位置**：`InventoryServiceImpl.reconcile` line 246

**现象**：reconcile 读 Redis（=98） vs DB（=100，因为对账时正好有未处理 deduct 消息），判定 diff=2 → 强制 set Redis=100。但这时如果有用户下单刚把 Redis 减到 97，被 set 覆盖回 100 → 超卖 3 件。

**修复**：reconcile 期间用 SETNX 做修复锁，或仅记录差异告警人工核对，不自动 set。

---

#### B10 — DLT **路由没配置**，重试逻辑形同虚设

**位置**：`InventoryListener` 所有 onXxx 方法 + `application.yml`

**现象**：代码定义了 `TOPIC_INVENTORY_DEDUCT_DLT` 但 `@KafkaListener` **没绑定** `RetryTopicConfiguration` 或 `DefaultErrorHandler`，消息异常时 throw 后只是默认行为（容器自带 SeekToCurrentErrorHandler 无限重试），从不进入 DLT。

`onRestore / onConfirm` topic 甚至**根本没定义 DLT topic**。

**修复**：注册 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`，配置最大重试 3 次后路由到 `.dlt`。

---

#### B11 — DLT 处理器**只打日志**

**位置**：`InventoryListener.onDeductDlt`

**现象**：仅 `log.error` + `ack`，没有持久化失败消息到 DB、没有发送告警通知。运维查不到失败列表。

**修复**：失败消息写入 `sc_dead_letter` 表 + 钉钉/邮件告警。

---

#### B12 — `InventoryListener.onDeduct` 数据不一致**被忽略**

**位置**：`InventoryListener.onDeduct` line 33

**现象**：`lockQty SQL` 因 `available_qty >= qty` 不成立 affected==0 时，只 `log.warn` **然后正常 ack**。Redis 已扣减，DB 没扣减 → 永久数据漂移直到对账。

**修复**：affected==0 时 throw 让消息进 DLT，人工介入。

---

#### B13 — 所有定时任务**无分布式锁**（多实例部署重复执行）

**位置**：`InventoryReconcileJob` / `ExpiryWarningJob` / `ReplenishmentCheckJob` / `KingdeeDataSyncJob`

**现象**：`@Scheduled` 是单 JVM 内调度。如果将来 sc-app 部署多副本（K8s、多容器），所有副本都会跑同一个任务 → 重复对账（互相覆盖）/ 重复生成补货单 / 重复发预警短信。

**修复**：引入 ShedLock + Redis/MySQL 后端，或用 Redisson 分布式锁守护任务入口。

---

#### B14 — `getOrCreateInventory` **首次入库并发抛 DuplicateKey**

**位置**：`InventoryServiceImpl.getOrCreateInventory`

**现象**：先 select → null → insert。两个并发首次入库同一 SKU+仓库都走 insert → unique key `uk_sku_wh` 抛 `DuplicateKeyException` → 一个事务失败回滚。

**修复**：改用 `INSERT ... ON DUPLICATE KEY UPDATE`，或 try-catch 捕获后重新 select。

---

#### B15 — `getAvailableFromRedis` **缓存未命中误判为 0**

**位置**：`InventoryServiceImpl.getAvailableFromRedis` 被 `ReplenishmentServiceImpl.checkAndTrigger` 调用

**现象**：Redis 没预热（应用刚启动 / Redis 重启）时 `getAvailableFromRedis` 返回 0，`checkAndTrigger` 当作"库存归零"立即触发**误补货**。

**修复**：返回 -1 / Optional.empty() 表示未知；调用方 fallback 查 DB，或先 warmup。

---

### 🟢 Medium（建议修）

#### B16 — `orderNo` UUID 截断到 16 字符**有重复风险**

**位置**：`FulfillmentServiceImpl.createOrder` line 38：`IdUtil.fastSimpleUUID().toUpperCase().substring(0, 16)`

UUID 截断后哈希空间从 2^128 缩到 2^64，大量并发下单时**理论可能冲突**。订单表 `order_no` 有 UNIQUE 约束，冲突时直接报错。

**修复**：用全 UUID 或雪花 ID（`IdUtil.getSnowflake().nextIdStr()`）。

---

#### B17 — `ProductService.onSale/offSale` **无并发保护**

并发上下架不会冲突（最终结果一致），但流水会写两条；如果未来加预校验"必须有库存"，就会被并发绕过。

**修复**：用 `WHERE id=? AND status=?` 条件 update，affected==0 抛已变更。

---

#### B18 — `freshType` 字段**无枚举校验**

`SpuRequest.freshType` 是 String，传任意值都会被接受，DB 存脏数据。

**修复**：DTO 加 `@Pattern` 或转 `FreshType` enum。

---

#### B19 — `InventoryServiceImpl` 有未使用的 `RedisTemplate<String,Object>` 字段（dead code）

line 43。该字段是 Bug #1 修复前的旧引用，现已被 `stringRedisTemplate` 取代但未删除。

---

#### B20 — `inventoryMapper.lockQty` SQL 条件**与 Redis 已扣减冲突**

`UPDATE ... WHERE available_qty >= qty` 这个条件会导致 Kafka 消息处理时如果 DB 已被对账修复或并发 lockQty 扰动，affected==0 → 数据漂移（关联 B12）。

**修复**：去掉 `available_qty >= qty` 条件（接受 DB 临时为负，对账兜底），或改为乐观锁版本号。

---

#### B21 — `application.yml` Kafka **手动 ACK 配置可能缺失**

代码用 `Acknowledgment ack` 手动 ack，但 yml 必须配 `spring.kafka.listener.ack-mode: MANUAL_IMMEDIATE` + `enable-auto-commit: false` 才生效，需检查（当前若使用默认 BATCH 模式，Acknowledgment 调用其实没意义）。

---

### 已知但**不打算修**（理由：不影响核心演示）

| 不修原因 | 项目 |
|---------|------|
| 演示项目 | 配置中心、链路追踪、监控告警 |
| Phase 2 | 骑手分配算法、金蝶推送 HTTP 实现 |
| 单测 | Service 层单元测试（Mockito + Testcontainers）|

---

### 修复优先级建议

| 优先级 | Bug | 一句话 |
|--------|-----|--------|
| 🔴 P0 | B1 | Kafka 消费者加幂等键（log 表唯一索引）|
| 🔴 P0 | B3 | cancelOrder 改成条件 update |
| 🔴 P0 | B4 | cancelOrder 状态白名单收紧到 PENDING/PAID |
| 🔴 P0 | B5 | inbound Redis 改用 INCRBY |
| 🔴 P0 | B7 | warmup 用 SETNX |
| 🟡 P1 | B6 | createOrder 改 AFTER_COMMIT 发 Kafka |
| 🟡 P1 | B8 | allocateFifo 加 Redisson 锁 |
| 🟡 P1 | B10/B11/B12 | DLT 路由 + 持久化 + affected==0 throw |
| 🟡 P1 | B13 | 定时任务接 ShedLock |
| 🟢 P2 | B14/B15/B16/B17/B18/B19/B20/B21 | 代码质量 |
