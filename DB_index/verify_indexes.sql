-- ============================================================================
-- 索引验证和测试脚本
-- 用途: 验证新索引是否正确创建，并测试查询性能
-- ============================================================================

SET SERVEROUTPUT ON;
SET LINESIZE 200;
SET PAGESIZE 1000;

-- ============================================================================
-- 第一部分: 清理旧索引
-- ============================================================================
PROMPT ========================================
PROMPT 步骤1: 清理旧索引
PROMPT ========================================

-- 清理 ZZ_PSUB_REF_diff_temp 的旧索引
DECLARE
    v_count NUMBER;
BEGIN
    FOR idx IN (
        SELECT index_name
        FROM user_indexes
        WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP'
        AND index_name IN (
            'IDX_ZZ_PSUB_REF_DIFF_TEMP_STAT',
            'IDX_DIFF_TEMP_MAIN',
            'IDX_DIFF_TEMP_COALESCE_KEY',
            'IDX_DIFF_TEMP_OLD_ONLY',
            'IDX_DIFF_TEMP_NEW_ONLY',
            'IDX_DIFF_TEMP_EXPORT'
        )
    ) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
            DBMS_OUTPUT.PUT_LINE('✓ 已删除索引: ' || idx.index_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('✗ 删除索引失败: ' || idx.index_name || ' - ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- 清理 zz_pos_item_diff_temp 的旧索引
DECLARE
    v_count NUMBER;
BEGIN
    FOR idx IN (
        SELECT index_name
        FROM user_indexes
        WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
        AND index_name IN (
            'IDX_POS_DIFF_TEMP_DATE',
            'IDX_POS_DIFF_TEMP_QUERY',
            'IDX_POS_DIFF_TEMP_STATUS1',
            'IDX_POS_DIFF_TEMP_STATUS2'
        )
    ) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
            DBMS_OUTPUT.PUT_LINE('✓ 已删除索引: ' || idx.index_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('✗ 删除索引失败: ' || idx.index_name || ' - ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- ============================================================================
-- 第二部分: 验证表结构
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤2: 验证表结构
PROMPT ========================================

-- 验证 ZZ_PSUB_REF_diff_temp 表
SELECT 'ZZ_PSUB_REF_diff_temp' AS table_name, COUNT(*) AS column_count
FROM user_tab_columns
WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP';

-- 验证 zz_pos_item_diff_temp 表
SELECT 'zz_pos_item_diff_temp' AS table_name, COUNT(*) AS column_count
FROM user_tab_columns
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- ============================================================================
-- 第三部分: 查看当前索引（创建新索引前）
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤3: 当前索引状态（创建新索引前）
PROMPT ========================================

SELECT table_name, index_name, status, uniqueness
FROM user_indexes
WHERE table_name IN ('ZZ_PSUB_REF_DIFF_TEMP', 'ZZ_POS_ITEM_DIFF_TEMP')
ORDER BY table_name, index_name;

-- ============================================================================
-- 第四部分: 执行创建索引（提示信息）
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤4: 请执行以下脚本创建索引
PROMPT ========================================
PROMPT @create_ZZ_PSUB_REF_DIFF_temp.sql
PROMPT @create_zz_pos_item_diff_temp.sql
PROMPT
PROMPT 执行完成后，按回车继续验证...
PAUSE

-- ============================================================================
-- 第五部分: 验证新索引创建
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤5: 验证新索引创建
PROMPT ========================================

-- 验证 ZZ_PSUB_REF_diff_temp 索引
PROMPT
PROMPT --- ZZ_PSUB_REF_diff_temp 索引 ---
SELECT
    index_name,
    status,
    index_type,
    uniqueness,
    compression,
    prefix_length
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP'
ORDER BY index_name;

-- 验证 zz_pos_item_diff_temp 索引
PROMPT
PROMPT --- zz_pos_item_diff_temp 索引 ---
SELECT
    index_name,
    status,
    index_type,
    uniqueness,
    compression,
    prefix_length
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
ORDER BY index_name;

-- ============================================================================
-- 第六部分: 查看索引列详情
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤6: 索引列详情
PROMPT ========================================

-- ZZ_PSUB_REF_diff_temp 索引列
PROMPT
PROMPT --- ZZ_PSUB_REF_diff_temp 索引列 ---
SELECT
    ic.index_name,
    ic.column_position,
    ic.column_name,
    ie.column_expression
FROM user_ind_columns ic
LEFT JOIN user_ind_expressions ie
    ON ic.index_name = ie.index_name
    AND ic.column_position = ie.column_position
WHERE ic.table_name = 'ZZ_PSUB_REF_DIFF_TEMP'
ORDER BY ic.index_name, ic.column_position;

-- zz_pos_item_diff_temp 索引列
PROMPT
PROMPT --- zz_pos_item_diff_temp 索引列 ---
SELECT
    ic.index_name,
    ic.column_position,
    ic.column_name,
    ie.column_expression
FROM user_ind_columns ic
LEFT JOIN user_ind_expressions ie
    ON ic.index_name = ie.index_name
    AND ic.column_position = ie.column_position
WHERE ic.table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
ORDER BY ic.index_name, ic.column_position;

-- ============================================================================
-- 第七部分: 索引空间占用
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤7: 索引空间占用
PROMPT ========================================

SELECT
    segment_name AS index_name,
    ROUND(bytes/1024/1024, 2) AS size_mb,
    tablespace_name
FROM user_segments
WHERE segment_type = 'INDEX'
AND segment_name IN (
    SELECT index_name
    FROM user_indexes
    WHERE table_name IN ('ZZ_PSUB_REF_DIFF_TEMP', 'ZZ_POS_ITEM_DIFF_TEMP')
)
ORDER BY bytes DESC;

-- ============================================================================
-- 第八部分: 收集统计信息
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤8: 收集统计信息
PROMPT ========================================

BEGIN
    DBMS_OUTPUT.PUT_LINE('正在收集 ZZ_PSUB_REF_diff_temp 统计信息...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => USER,
        tabname => 'ZZ_PSUB_REF_DIFF_TEMP',
        cascade => TRUE,
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO'
    );
    DBMS_OUTPUT.PUT_LINE('✓ ZZ_PSUB_REF_diff_temp 统计信息收集完成');

    DBMS_OUTPUT.PUT_LINE('正在收集 zz_pos_item_diff_temp 统计信息...');
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname => USER,
        tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
        cascade => TRUE,
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt => 'FOR ALL COLUMNS SIZE AUTO'
    );
    DBMS_OUTPUT.PUT_LINE('✓ zz_pos_item_diff_temp 统计信息收集完成');
END;
/

-- ============================================================================
-- 第九部分: 测试查询性能
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤9: 测试查询执行计划
PROMPT ========================================

-- 测试1: ZZ_PSUB_REF_diff_temp 主查询
PROMPT
PROMPT --- 测试1: ZZ_PSUB_REF_diff_temp 主查询 ---
EXPLAIN PLAN FOR
SELECT SUBSCRIBER,ACCOUNT_NO,CELLULAR,STAT,SIM_NO,IMEI,UNBILL_AMT,UNBILL_AMT_SIGN,ACT_DATE,S_OFF_DATE,BILL_DAY,DEALER,CUST_TYPE,
       RPLAN,DISCON_REASON,LAST_PAYMENT_DATE,PAYMENT_METHOD,DIVERT_CODE,SUBSCRIBER_NB,ACCOUNT_NO_NB,CELLULAR_NB,STAT_NB,SIM_NO_NB,IMEI_NB,
       UNBILL_AMT_NB,UNBILL_AMT_SIGN_NB,ACT_DATE_NB,S_OFF_DATE_NB,BILL_DAY_NB,DEALER_NB,CUST_TYPE_NB,RPLAN_NB,DISCON_REASON_NB,LAST_PAYMENT_DATE_NB,
       PAYMENT_METHOD_NB,DIVERT_CODE_NB,REMARKS,COMPARE_STATUS,CREATE_DATE
FROM ZZ_PSUB_REF_diff_temp
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE)
ORDER BY compare_status, subscriber, cellular;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +PREDICATE'));

-- 测试2: zz_pos_item_diff_temp 主查询
PROMPT
PROMPT --- 测试2: zz_pos_item_diff_temp 主查询 ---
EXPLAIN PLAN FOR
SELECT subscriber, cellular, invoice_no, inv_date, inv_type, inv_amount, os_amount, charge_type,
       subscriber_nb, cellular_nb, invoice_no_nb, inv_date_nb, inv_type_nb, inv_amount_nb, os_amount_nb, charge_type_nb,
       compare_status, remarks, create_date
FROM zz_pos_item_diff_temp
WHERE TRUNC(create_date) = TRUNC(SYSDATE)
ORDER BY compare_status, subscriber, cellular, invoice_no;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +PREDICATE'));

-- 测试3: 按状态查询 (compare_status = '1')
PROMPT
PROMPT --- 测试3: 按状态查询 (compare_status = '1') ---
EXPLAIN PLAN FOR
SELECT subscriber, cellular
FROM ZZ_PSUB_REF_diff_temp
WHERE compare_status = '1'
AND TRUNC(create_date) = TRUNC(SYSDATE);

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +PREDICATE'));

-- 测试4: 按状态查询 (compare_status = '2')
PROMPT
PROMPT --- 测试4: 按状态查询 (compare_status = '2') ---
EXPLAIN PLAN FOR
SELECT subscriber_nb, cellular_nb
FROM ZZ_PSUB_REF_diff_temp
WHERE compare_status = '2'
AND TRUNC(create_date) = TRUNC(SYSDATE);

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +PREDICATE'));

-- ============================================================================
-- 第十部分: 验证总结
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 步骤10: 验证总结
PROMPT ========================================

-- 统计索引数量
SELECT
    'ZZ_PSUB_REF_diff_temp' AS table_name,
    COUNT(*) AS index_count
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP'
UNION ALL
SELECT
    'zz_pos_item_diff_temp' AS table_name,
    COUNT(*) AS index_count
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- 检查是否有失效索引
SELECT
    table_name,
    index_name,
    status
FROM user_indexes
WHERE table_name IN ('ZZ_PSUB_REF_DIFF_TEMP', 'ZZ_POS_ITEM_DIFF_TEMP')
AND status != 'VALID'
ORDER BY table_name, index_name;

-- ============================================================================
-- 完成
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT ✓ 索引验证完成！
PROMPT ========================================
PROMPT
PROMPT 预期结果:
PROMPT 1. ZZ_PSUB_REF_diff_temp: 5个索引（全部VALID）
PROMPT 2. zz_pos_item_diff_temp: 5个索引（全部VALID）
PROMPT 3. 主查询应使用 idx_diff_temp_export_main 或 idx_pos_diff_export_main
PROMPT 4. 无失效索引
PROMPT
PROMPT 如有问题，请检查:
PROMPT - 索引是否创建成功（检查user_indexes）
PROMPT - 是否有权限问题（检查user_errors）
PROMPT - 表空间是否足够（检查user_tablespaces）
PROMPT

