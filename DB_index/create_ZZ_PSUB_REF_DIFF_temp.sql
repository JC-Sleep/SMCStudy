CREATE TABLE ZZ_PSUB_REF_diff_temp (
    Subscriber VARCHAR2(20),
    Account_no VARCHAR2(20),
    Cellular VARCHAR2(20),
    Stat VARCHAR2(1),
    Sim_No VARCHAR2(20),
    IMEI VARCHAR2(20),
    Unbill_amt NUMBER(12,2),
    Unbill_amt_sign VARCHAR2(1),
    Act_date DATE,
    S_Off_date DATE,
    Bill_day NUMBER(5,0),
    DEALER VARCHAR2(20),
    CUST_TYPE VARCHAR2(40),
    RPLAN VARCHAR2(5),
    DISCON_REASON VARCHAR2(40),
    LAST_PAYMENT_DATE DATE,
    PAYMENT_METHOD  VARCHAR2(40),
    DIVERT_CODE VARCHAR2(15),
    Subscriber_NB VARCHAR2(20),
    Account_no_NB VARCHAR2(20),
    Cellular_NB VARCHAR2(20),
    Stat_NB VARCHAR2(1),
    Sim_No_NB VARCHAR2(20),
    IMEI_NB VARCHAR2(20),
    Unbill_amt_NB NUMBER(12,2),
    Unbill_amt_sign_NB VARCHAR2(1),
    Act_date_NB DATE,
    S_Off_date_NB DATE,
    Bill_day_NB NUMBER(5,0),
    DEALER_NB VARCHAR2(20),
    CUST_TYPE_NB VARCHAR2(40),
    RPLAN_NB VARCHAR2(5),
    DISCON_REASON_NB VARCHAR2(40),
    LAST_PAYMENT_DATE_NB DATE,
    PAYMENT_METHOD_NB  VARCHAR2(40),
    DIVERT_CODE_NB VARCHAR2(15),
    remarks  VARCHAR2(100),
    compare_status VARCHAR2(2),
    create_date DATE DEFAULT SYSDATE
);
-- ============================================================================
-- 索引设计说明 (Oracle优化版本)
-- ============================================================================
-- 1. 主查询索引：支持导出查询 WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) ORDER BY compare_status, subscriber, cellular
-- 2. 复合索引：避免冗余，基于实际查询模式设计
-- 3. 函数索引：针对TRUNC(create_date)优化查询性能
-- ============================================================================

-- 【核心索引1】函数索引 - 支持按日期过滤（最常用）
CREATE INDEX idx_diff_temp_trunc_date ON ZZ_PSUB_REF_diff_temp(TRUNC(create_date));

-- 【核心索引2】复合索引 - 支持导出查询的WHERE + ORDER BY
-- 覆盖查询：WHERE TRUNC(create_date) = ? ORDER BY compare_status, subscriber, cellular
CREATE INDEX idx_diff_temp_export_main ON ZZ_PSUB_REF_diff_temp(
    TRUNC(create_date),
    compare_status,
    Subscriber,
    Cellular
);

-- 【辅助索引3】比较状态索引 - 用于快速分类统计
CREATE INDEX idx_diff_temp_status ON ZZ_PSUB_REF_diff_temp(compare_status);

-- 【辅助索引4】旧系统数据索引（compare_status='1'时使用）
CREATE INDEX idx_diff_temp_old_data ON ZZ_PSUB_REF_diff_temp(
    compare_status,
    Subscriber,
    Cellular
)
COMPRESS 1;  -- 压缩第一列（compare_status重复率高）

-- 【辅助索引5】新系统数据索引（compare_status='2'时使用）
CREATE INDEX idx_diff_temp_new_data ON ZZ_PSUB_REF_diff_temp(
    compare_status,
    Subscriber_NB,
    Cellular_NB
)
COMPRESS 1;  -- 压缩第一列（compare_status重复率高）


DROP PUBLIC SYNONYM ZZ_PSUB_REF_diff_temp;

CREATE PUBLIC SYNONYM ZZ_PSUB_REF_diff_temp FOR FES.ZZ_PSUB_REF_diff_temp;


GRANT DELETE, INSERT, SELECT, UPDATE ON FES.ZZ_PSUB_REF_diff_temp TO FESJUPLD;

GRANT DELETE, INSERT, SELECT, UPDATE ON FES.ZZ_PSUB_REF_diff_temp TO FES_BATCH_ROLE;

GRANT DELETE, INSERT, SELECT, UPDATE ON FES.ZZ_PSUB_REF_diff_temp TO FES_SA_ROLE;

GRANT DELETE, INSERT, SELECT, UPDATE ON FES.ZZ_PSUB_REF_diff_temp TO FES_USER_ROLE;

