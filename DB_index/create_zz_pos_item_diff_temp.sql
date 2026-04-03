CREATE TABLE zz_pos_item_diff_temp (
    -- Old system fields
    subscriber        VARCHAR2(20),
    cellular          VARCHAR2(20),
    invoice_no        VARCHAR2(10),
    inv_date          DATE,
    inv_type          VARCHAR2(3),
    inv_amount        NUMBER(12,2),
    os_amount         NUMBER(12,2),
    charge_type       VARCHAR2(10),

    -- New system fields
    subscriber_nb     VARCHAR2(20),
    cellular_nb       VARCHAR2(20),
    invoice_no_nb     VARCHAR2(10),
    inv_date_nb       DATE,
    inv_type_nb       VARCHAR2(3),
    inv_amount_nb     NUMBER(12,2),
    os_amount_nb      NUMBER(12,2),
    charge_type_nb    VARCHAR2(10),

    -- Comparison metadata
    compare_status    VARCHAR2(2) ,  -- 1: exist in old but not new, 2: exist in new but not old
    remarks           VARCHAR2(100),
    create_date   DATE DEFAULT SYSDATE
);

-- ============================================================================
-- 索引设计说明 (Oracle优化版本)
-- ============================================================================
-- 1. 主查询索引：支持导出查询 WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) ORDER BY compare_status, subscriber, cellular, invoice_no
-- 2. 复合索引：避免冗余，基于实际查询模式设计
-- 3. 函数索引：针对TRUNC(create_date)优化查询性能
-- 4. 索引压缩：针对重复率高的列使用COMPRESS
-- ============================================================================

-- 【核心索引1】函数索引 - 支持按日期过滤（最常用）
CREATE INDEX idx_pos_diff_trunc_date ON zz_pos_item_diff_temp(TRUNC(create_date));

-- 【核心索引2】复合索引 - 支持导出查询的WHERE + ORDER BY
-- 覆盖查询：WHERE TRUNC(create_date) = ? ORDER BY compare_status, subscriber, cellular, invoice_no
CREATE INDEX idx_pos_diff_export_main ON zz_pos_item_diff_temp(
    TRUNC(create_date),
    compare_status,
    subscriber,
    cellular,
    invoice_no
);

-- 【辅助索引3】比较状态索引 - 用于快速分类统计
CREATE INDEX idx_pos_diff_status ON zz_pos_item_diff_temp(compare_status);

-- 【辅助索引4】旧系统数据索引（compare_status='1'时使用）
CREATE INDEX idx_pos_diff_old_data ON zz_pos_item_diff_temp(
    compare_status,
    subscriber,
    cellular,
    invoice_no
)
COMPRESS 1;  -- 压缩第一列（compare_status重复率高）

-- 【辅助索引5】新系统数据索引（compare_status='2'时使用）
CREATE INDEX idx_pos_diff_new_data ON zz_pos_item_diff_temp(
    compare_status,
    subscriber_nb,
    cellular_nb,
    invoice_no_nb
)
COMPRESS 1;  -- 压缩第一列（compare_status重复率高）


DROP PUBLIC SYNONYM zz_pos_item_diff_temp;

CREATE PUBLIC SYNONYM zz_pos_item_diff_temp FOR FES.zz_pos_item_diff_temp;


GRANT DELETE, INSERT, SELECT, UPDATE ON FES.zz_pos_item_diff_temp TO FESJUPLD;

GRANT DELETE, INSERT, SELECT, UPDATE ON FES.zz_pos_item_diff_temp TO FES_BATCH_ROLE;

GRANT DELETE, INSERT, SELECT, UPDATE ON FES.zz_pos_item_diff_temp TO FES_SA_ROLE;

GRANT DELETE, INSERT, SELECT, UPDATE ON FES.zz_pos_item_diff_temp TO FES_USER_ROLE;
