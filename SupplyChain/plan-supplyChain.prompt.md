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
| 数据库 | **MySQL 8.0**（mysql-connector-j 8.0.33） | 社区小店轻量场景，运维成本低，开发调试友好；支持 JSON 列、窗口函数 |
| 缓存 | Redis + Redisson 3.17.7 | 库存预扣原子操作 + 分布式锁 |
| 消息队列 | Kafka（主）| 库存/补货/预警异步解耦 |
| 连接池 | Druid 1.2.20 | SQL 监控 + 慢查询告警 |
| 工具 | Lombok、Hutool、Fastjson2 | - |
| API文档 | Knife4j 3.0.3（Swagger UI） | - |
| 监控 | Spring Boot Actuator | - |
| 定时任务 | Spring @Scheduled | 生产可升级 XXL-Job |
| HTTP Client | Spring RestTemplate / OkHttp | 金蝶云接口外壳预留 |

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

4. **分布式锁粒度** — 锁 key 为 `inventory:lock:{warehouseId}:{skuId}`，粒度到 SKU+仓库，避免全局锁；Lua Script 比 Redisson 延迟更低，优先用 Lua，超复杂事务才用 Redisson。

5. **生鲜预警天数配置化** — 预警天数（3/7天）通过 `application.yml` `supply-chain.expiry.warn-days-near=7` / `warn-days-urgent=3` 配置，`@ConfigurationProperties` 注入，不同品类阈值后期升级为 DB 配置表。

6. **MySQL 连接池配置** — Druid + `spring.datasource.url=jdbc:mysql://localhost:3306/sc_supply_chain?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai&useSSL=false`，`testWhileIdle=true` 防连接扔失。



## 自己写的，记录
✅ 执行完成 — 旺生活 O2O 供应链中台
文件统计：74 个 Java 文件 + 3 个资源文件 + 1 个 pom.xml

### 📦 模块结构

| 包/目录                                                                                                                                                                                                         |   内容 |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| config/                                                                                                                                                                                                      |RedisConfig（Lua脚本注册）、KafkaConfig（7个Topic+DLT）、MybatisPlusConfig、MetaObjectFillHandler、SupplyChainProperties、SwaggerConfig |
| entity/                                                                                                                                                                                                      | 16个实体：Spu、Sku、SpuCategory、Warehouse、Inventory、InventoryBatch、InventoryLog、ReplenishmentRule、ReplenishmentOrder、Store、FulfillmentOrder、FulfillmentRecord、ExpiryWarning + Phase2: Rider、DeliveryOrder、KingdeeSync |
| enums/| 7个枚举：ProductStatus、FreshType、BatchStatus（四级效期）、FulfillmentOrderStatus、InventoryOpType、ReplenishmentStatus、DeliveryStatus                                                                                        |
| mapper/                                                                                                                                                                                                      | 15个 Mapper（含 InventoryMapper 自定义 lockQty/unlockQty/confirmDeductQty、InventoryBatchMapper FIFO查询、ExpiryWarningMapper 临期扫描）                                                                                       |
| service/                                                                                                                                                                                                     | 4个服务：ProductService（上下架状态机）、InventoryService（Lua预扣+FIFO+对账）、ReplenishmentService（阈值扫描）、FulfillmentService（全链路履约）                                                                                               |
| controller/                                                                                                                                                                                                  | 6个接口：Product、Inventory、Replenishment、Fulfillment（含效期预警）、Delivery（Phase2外壳）、Kingdee（Phase2外壳）                                                                                                                  |
| mq/                                                                                                                                                                                                          | InventoryEventProducer（deduct/restore/confirm/replenishment）                                                                                                                                                  |
| listener/                                                                                                                                                                                                    | InventoryListener（DEDUCT/RESTORE/CONFIRM落库+DLT告警）、ReplenishmentListener（生成补货单）                                                                                                                                  |
| job/                                                                                                                                                                                                         | ReplenishmentCheckJob（每30分钟）、ExpiryWarningJob（每天早8点四级预警）、InventoryReconcileJob（每天凌晨2点对账）、KingdeeDataSyncJob（Phase2外壳）                                                                                           |
| integration/                                                                                                                                                                                                 | KingdeeApiClient（Phase2外壳，3种凭证方法）                                                                                                                                                                              |
| exception/                                                                                                                                                                                                   | SupplyChainException（stockInsufficient/notFound/illegalStatus）、GlobalExceptionHandler                                                                                                                          |
| util/                                                                                                                                                                                                        | RedisKeyUtil（所有Redis Key常量）   |                                                                                                                                                                                  |
| resources                                                                                                                                                                                                    | application.yml（MySQL 8/Redis/Kafka/供应链配置）、lua/inventory_lock.lua（防超卖Lua）、db/init.sql（全量MySQL8 DDL + 测试数据） |                                                                                                     |
### 🔑 核心技术实现
防超卖：inventory_lock.lua Lua Script 原子检查+DECRBY，返回 -1（不足）/ -2（未预热）/ ≥0（成功）
FIFO出库：InventoryBatchMapper.selectFifoBatches() + allocateFifo() 跨批次分配
异步落库：预扣成功后 Kafka → InventoryListener 消费更新DB，失败重试3次→DLT
定时对账：凌晨2点全量扫描 Redis vs DB，以DB为准修复，写对账流水
效期四级：ExpiryWarningJob 每日8点扫描，NORMAL/NEAR_EXPIRY/URGENT/EXPIRED 分级处理



