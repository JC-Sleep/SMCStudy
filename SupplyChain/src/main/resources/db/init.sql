-- ============================================================
-- 旺生活 O2O 供应链中台 - MySQL 8.0 DDL 初始化脚本
-- 执行前：CREATE DATABASE sc_supply_chain DEFAULT CHARSET utf8mb4;
--         USE sc_supply_chain;
-- ============================================================

-- 商品分类表（多级树）
CREATE TABLE IF NOT EXISTS sc_category (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '分类ID（雪花）',
    parent_id   BIGINT       NOT NULL DEFAULT 0   COMMENT '父分类ID，0=顶级',
    cat_name    VARCHAR(100) NOT NULL              COMMENT '分类名',
    level       TINYINT      NOT NULL DEFAULT 1    COMMENT '层级(1/2/3)',
    sort        INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent(parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

-- SPU 商品表
CREATE TABLE IF NOT EXISTS sc_spu (
    id          BIGINT        NOT NULL PRIMARY KEY,
    spu_name    VARCHAR(200)  NOT NULL              COMMENT '商品名称',
    category_id BIGINT                              COMMENT '分类ID',
    brand       VARCHAR(100),
    fresh_type  VARCHAR(20)   NOT NULL DEFAULT 'NORMAL'  COMMENT 'NORMAL/FRESH/FROZEN/CHILLED',
    status      VARCHAR(20)   NOT NULL DEFAULT 'DRAFT'   COMMENT 'DRAFT/ON_SALE/OFF_SALE',
    description TEXT,
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT       NOT NULL DEFAULT 0,
    INDEX idx_category(category_id),
    INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准商品单元 SPU';

-- SKU 表（含 JSON 多级属性）
CREATE TABLE IF NOT EXISTS sc_sku (
    id          BIGINT        NOT NULL PRIMARY KEY,
    spu_id      BIGINT        NOT NULL              COMMENT '关联SPU',
    sku_name    VARCHAR(200),
    sku_attrs   JSON                                COMMENT '[{"attrKey":"规格","attrVal":"500g"},...]',
    price       DECIMAL(12,2)                       COMMENT '售价',
    weight      DECIMAL(10,3)                       COMMENT '克重',
    img_url     VARCHAR(500),
    status      VARCHAR(20)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT       NOT NULL DEFAULT 0,
    INDEX idx_spu(spu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存单位 SKU';

-- 仓库表
CREATE TABLE IF NOT EXISTS sc_warehouse (
    id             BIGINT       NOT NULL PRIMARY KEY,
    warehouse_name VARCHAR(100) NOT NULL,
    address        VARCHAR(300),
    type           VARCHAR(20)  NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN=大仓/STORE=小店仓',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

-- 库存表（仓库级，与 Redis 镜像最终一致）
CREATE TABLE IF NOT EXISTS sc_inventory (
    id             BIGINT   NOT NULL PRIMARY KEY,
    sku_id         BIGINT   NOT NULL,
    warehouse_id   BIGINT   NOT NULL,
    available_qty  INT      NOT NULL DEFAULT 0  COMMENT '可用库存（Redis镜像）',
    locked_qty     INT      NOT NULL DEFAULT 0  COMMENT '锁定库存（预扣待出库）',
    total_qty      INT      NOT NULL DEFAULT 0  COMMENT '总库存',
    update_time    DATETIME ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_wh(sku_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库级库存';

-- 库存批次表（FIFO + 效期管理）
CREATE TABLE IF NOT EXISTS sc_inventory_batch (
    id           BIGINT      NOT NULL PRIMARY KEY,
    batch_no     VARCHAR(64) NOT NULL UNIQUE     COMMENT '批次号',
    sku_id       BIGINT      NOT NULL,
    warehouse_id BIGINT      NOT NULL,
    inbound_qty  INT         NOT NULL            COMMENT '入库数量',
    remain_qty   INT         NOT NULL            COMMENT '剩余数量',
    product_date DATE                            COMMENT '生产日期',
    expire_date  DATE                            COMMENT '过期日期',
    inbound_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'FIFO排序依据',
    status       VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/NEAR_EXPIRY/URGENT/EXPIRED/WRITTEN_OFF',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sku_wh_expire(sku_id, warehouse_id, expire_date),
    INDEX idx_inbound_time(inbound_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存批次 FIFO/效期';

-- 库存流水（全量审计）
CREATE TABLE IF NOT EXISTS sc_inventory_log (
    id           BIGINT      NOT NULL PRIMARY KEY,
    sku_id       BIGINT,
    warehouse_id BIGINT,
    batch_no     VARCHAR(64),
    op_type      VARCHAR(30) NOT NULL COMMENT 'INBOUND/LOCK/UNLOCK/CONFIRM/WRITEOFF/RECONCILE_FIX',
    qty_before   INT,
    qty_after    INT,
    delta_qty    INT,
    ref_no       VARCHAR(64)             COMMENT '关联单号',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sku(sku_id),
    INDEX idx_ref(ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存操作流水';

-- 自动补货规则
CREATE TABLE IF NOT EXISTS sc_replenishment_rule (
    id             BIGINT   NOT NULL PRIMARY KEY,
    sku_id         BIGINT   NOT NULL,
    warehouse_id   BIGINT   NOT NULL,
    min_qty        INT      NOT NULL  COMMENT '触发阈值',
    replenish_qty  INT      NOT NULL  COMMENT '每次补货量',
    supplier_id    BIGINT             COMMENT '供应商ID',
    is_enabled     TINYINT  NOT NULL DEFAULT 1,
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_wh(sku_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动补货规则';

-- 补货单
CREATE TABLE IF NOT EXISTS sc_replenishment_order (
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
CREATE TABLE IF NOT EXISTS sc_store (
    id           BIGINT       NOT NULL PRIMARY KEY,
    store_name   VARCHAR(200) NOT NULL,
    community_id BIGINT                    COMMENT '社区ID',
    address      VARCHAR(300),
    phone        VARCHAR(20),
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区小店';

-- O2O 履约订单
CREATE TABLE IF NOT EXISTS sc_fulfillment_order (
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

-- 履约操作流水
CREATE TABLE IF NOT EXISTS sc_fulfillment_record (
    id          BIGINT       NOT NULL PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    op_type     VARCHAR(30)  NOT NULL COMMENT 'CREATE/PAY/PICK/OUTBOUND/DELIVER/CANCEL',
    op_by       VARCHAR(50),
    remark      VARCHAR(300),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约操作流水';

-- 效期预警记录
CREATE TABLE IF NOT EXISTS sc_expiry_warning (
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

-- 骑手
CREATE TABLE IF NOT EXISTS sc_rider (
    id          BIGINT       NOT NULL PRIMARY KEY,
    rider_name  VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)  NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/BUSY/OFFLINE',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手【Phase2预留】';

-- 配送单
CREATE TABLE IF NOT EXISTS sc_delivery_order (
    id                   BIGINT      NOT NULL PRIMARY KEY,
    fulfillment_order_id BIGINT      NOT NULL COMMENT '关联履约订单',
    rider_id             BIGINT                COMMENT '分配骑手',
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                     COMMENT 'PENDING/ASSIGNED/PICKING/IN_TRANSIT/DELIVERED/FAILED',
    assign_time          DATETIME,
    pickup_time          DATETIME,
    delivered_time       DATETIME,
    fail_reason          VARCHAR(200),
    create_time          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order(fulfillment_order_id),
    INDEX idx_rider(rider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送单【Phase2预留】';

-- 金蝶云同步记录
CREATE TABLE IF NOT EXISTS sc_kingdee_sync (
    id            BIGINT      NOT NULL PRIMARY KEY,
    sync_type     VARCHAR(30) NOT NULL COMMENT 'SALES_VOUCHER/COST_VOUCHER/AP_VOUCHER',
    ref_no        VARCHAR(64)           COMMENT '来源单号',
    sync_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    request_body  TEXT                  COMMENT '请求报文快照',
    response_body TEXT                  COMMENT '响应报文快照',
    fail_reason   VARCHAR(500),
    retry_count   INT         NOT NULL DEFAULT 0,
    sync_time     DATETIME,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ref(ref_no),
    INDEX idx_status(sync_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='金蝶云同步记录【Phase2预留】';

-- ============================================================
-- 测试数据（可选）
-- ============================================================
INSERT IGNORE INTO sc_warehouse (id, warehouse_name, address, type)
VALUES (1, '旺生活广州中央大仓', '广州市番禺区碧桂园大仓', 'MAIN');

INSERT IGNORE INTO sc_category (id, parent_id, cat_name, level, sort)
VALUES (1, 0, '生鲜食品', 1, 1),
       (2, 1, '蔬菜', 2, 1),
       (3, 1, '水果', 2, 2),
       (4, 0, '粮油调味', 1, 2);
