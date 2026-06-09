-- ============================================================
-- Phase 2.1 — 骑手配送模块 DDL（增量）
-- 执行：在 sc_supply_chain 数据库下运行
-- 顺序：先 ALTER 已有表，再 CREATE 新表
-- ============================================================

USE sc_supply_chain;

-- ── 1. 扩展 sc_rider 表 ─────────────────────────────────────────
ALTER TABLE sc_rider
    ADD COLUMN warehouse_id     BIGINT          COMMENT '所属仓库（限制只接该仓订单）',
    ADD COLUMN vehicle_type     VARCHAR(20)     NOT NULL DEFAULT 'EBIKE' COMMENT 'EBIKE/MOTOR/CAR/WALK',
    ADD COLUMN current_lng      DECIMAL(10,7)   COMMENT '当前经度',
    ADD COLUMN current_lat      DECIMAL(10,7)   COMMENT '当前纬度',
    ADD COLUMN max_parallel     INT             NOT NULL DEFAULT 3 COMMENT '同时配送上限',
    ADD COLUMN current_load     INT             NOT NULL DEFAULT 0 COMMENT '当前在途单数',
    ADD COLUMN rating           DECIMAL(3,2)    DEFAULT 5.00 COMMENT '骑手评分',
    ADD COLUMN today_orders     INT             NOT NULL DEFAULT 0 COMMENT '今日已完成单',
    ADD COLUMN online           TINYINT         NOT NULL DEFAULT 0 COMMENT '0=离线 1=在线',
    ADD COLUMN last_heartbeat   DATETIME        COMMENT '最后心跳',
    ADD INDEX idx_wh_status_online (warehouse_id, status, online);

-- ── 2. 扩展 sc_delivery_order 表 ─────────────────────────────────
ALTER TABLE sc_delivery_order
    ADD COLUMN warehouse_id          BIGINT          COMMENT '所属仓库',
    ADD COLUMN pickup_address        VARCHAR(300)    COMMENT '取货地址',
    ADD COLUMN pickup_lng            DECIMAL(10,7),
    ADD COLUMN pickup_lat            DECIMAL(10,7),
    ADD COLUMN deliver_address       VARCHAR(300)    COMMENT '收件地址',
    ADD COLUMN deliver_lng           DECIMAL(10,7),
    ADD COLUMN deliver_lat           DECIMAL(10,7),
    ADD COLUMN distance_meters       INT             COMMENT '直线距离米',
    ADD COLUMN estimated_minutes     INT             COMMENT '预估分钟',
    ADD COLUMN expected_pickup_time  DATETIME        COMMENT '应取货时间',
    ADD COLUMN expected_deliver_time DATETIME        COMMENT '应送达时间',
    ADD COLUMN pickup_code           VARCHAR(8)      COMMENT '取货码',
    ADD COLUMN deliver_code          VARCHAR(8)      COMMENT '签收码',
    ADD COLUMN base_fee              DECIMAL(8,2),
    ADD COLUMN distance_fee          DECIMAL(8,2),
    ADD COLUMN penalty_fee           DECIMAL(8,2)    DEFAULT 0,
    ADD COLUMN final_fee             DECIMAL(8,2),
    ADD COLUMN reassign_count        INT             NOT NULL DEFAULT 0,
    ADD COLUMN customer_remark       VARCHAR(300),
    ADD COLUMN customer_phone_masked VARCHAR(20),
    ADD INDEX idx_rider_status (rider_id, status),
    ADD INDEX idx_status_expected (status, expected_deliver_time),
    ADD INDEX idx_warehouse_status (warehouse_id, status);

-- ── 3. 抢单池 ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sc_delivery_grab_pool (
    id                  BIGINT PRIMARY KEY,
    delivery_id         BIGINT NOT NULL UNIQUE,
    warehouse_id        BIGINT,
    broadcast_lng       DECIMAL(10,7),
    broadcast_lat       DECIMAL(10,7),
    broadcast_radius_m  INT DEFAULT 3000,
    expire_time         DATETIME NOT NULL,
    grabbed_by          BIGINT COMMENT '抢到的骑手ID（NULL=未抢）',
    grab_time           DATETIME,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_wh_expire (warehouse_id, expire_time),
    INDEX idx_grabbed (grabbed_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢单池';

-- ── 4. 配送轨迹 ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sc_delivery_track (
    id          BIGINT PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    rider_id    BIGINT,
    lng         DECIMAL(10,7),
    lat         DECIMAL(10,7),
    speed_kmh   DECIMAL(5,2),
    report_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_delivery_time (delivery_id, report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送轨迹';

-- ── 5. 骑手收入流水 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sc_rider_income (
    id                BIGINT PRIMARY KEY,
    rider_id          BIGINT NOT NULL,
    delivery_id       BIGINT NOT NULL,
    base_fee          DECIMAL(8,2),
    distance_fee      DECIMAL(8,2),
    rush_hour_bonus   DECIMAL(8,2) DEFAULT 0,
    weather_bonus     DECIMAL(8,2) DEFAULT 0,
    penalty_fee       DECIMAL(8,2) DEFAULT 0,
    total             DECIMAL(8,2) NOT NULL,
    settle_status     VARCHAR(20)  DEFAULT 'PENDING',
    settle_time       DATETIME,
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rider_create (rider_id, create_time),
    INDEX idx_delivery (delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手收入流水';

-- ── 6. 配送异常工单 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sc_delivery_exception (
    id                BIGINT PRIMARY KEY,
    delivery_id       BIGINT NOT NULL,
    exception_type    VARCHAR(30) NOT NULL,
    exception_detail  VARCHAR(500),
    handler_id        BIGINT,
    handle_status     VARCHAR(20) DEFAULT 'OPEN',
    handle_remark     VARCHAR(500),
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handle_time       DATETIME,
    INDEX idx_status (handle_status),
    INDEX idx_delivery (delivery_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送异常工单';

