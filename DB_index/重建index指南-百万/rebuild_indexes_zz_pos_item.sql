-- ============================================================================
-- 重建索引完整流程（zz_pos_item_diff_temp表）
-- 适用场景：表有百万数据，需要重新创建索引
-- ============================================================================
-- 重要提示：
-- 1. DROP索引：很快（毫秒级），不需要特殊操作
-- 2. CREATE索引：百万数据需要时间（约1-5分钟），自动构建B树
-- 3. 索引信息：Oracle自动维护，不需要手动更新
-- 4. 统计信息：必须手动收集！否则优化器可能误判 ⚠️⚠️⚠️
-- ============================================================================

SET SERVEROUTPUT ON;
SET TIMING ON;
SET ECHO ON;

PROMPT ======================================================================
PROMPT 开始重建zz_pos_item_diff_temp表的所有索引
PROMPT 执行时间：
SELECT TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') as start_time FROM dual;
PROMPT ======================================================================

-- ============================================================================
-- 阶段1：备份当前索引定义（以防需要回滚）
-- ============================================================================
PROMPT
PROMPT ======== 阶段1：备份当前索引定义 ========

CREATE TABLE idx_backup_zz_pos_item_$(TO_CHAR(SYSDATE,'YYYYMMDD_HH24MISS')) AS
SELECT
    index_name,
    index_type,
    uniqueness,
    compression,
    num_rows,
    distinct_keys,
    last_analyzed,
    SYSDATE as backup_time
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

PROMPT ✓ 索引信息已备份

-- ============================================================================
-- 阶段2：删除旧索引
-- ============================================================================
PROMPT
PROMPT ======== 阶段2：删除旧索引 ========

BEGIN
    FOR idx IN (
        SELECT index_name
        FROM user_indexes
        WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
    ) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
            DBMS_OUTPUT.PUT_LINE('✓ 已删除: ' || idx.index_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('✗ 删除失败: ' || idx.index_name || ' - ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- 验证删除结果
SELECT
    CASE
        WHEN COUNT(*) = 0 THEN '✓ 所有旧索引已删除'
        ELSE '⚠️ 还有 ' || COUNT(*) || ' 个索引未删除'
    END as delete_status
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- ============================================================================
-- 阶段3：创建新索引（百万数据，预计1-5分钟）
-- ============================================================================
PROMPT
PROMPT ======== 阶段3：创建新索引（百万数据，请耐心等待...）========

-- 【核心索引1】函数索引 - 支持按日期过滤
PROMPT 创建索引1/5: idx_zz_pos_item_diff_temp_trunc_date...
CREATE INDEX idx_zz_pos_item_diff_temp_trunc_date
ON zz_pos_item_diff_temp(TRUNC(create_date));
PROMPT ✓ 完成

-- 【核心索引2】复合索引 - 支持导出查询
PROMPT 创建索引2/5: idx_zz_pos_item_diff_temp_export_main...
CREATE INDEX idx_zz_pos_item_diff_temp_export_main
ON zz_pos_item_diff_temp(
    TRUNC(create_date),
    compare_status
);
PROMPT ✓ 完成

-- 【辅助索引3】比较状态索引
PROMPT 创建索引3/5: idx_zz_pos_item_diff_temp_status...
CREATE INDEX idx_zz_pos_item_diff_temp_status
ON zz_pos_item_diff_temp(compare_status);
PROMPT ✓ 完成

-- 【辅助索引4】旧系统数据索引
PROMPT 创建索引4/5: idx_zz_pos_item_diff_temp_old_data...
CREATE INDEX idx_zz_pos_item_diff_temp_old_data
ON zz_pos_item_diff_temp(
    compare_status,
    subscriber,
    cellular,
    invoice_no
)
COMPRESS 1;
PROMPT ✓ 完成

-- 【辅助索引5】新系统数据索引
PROMPT 创建索引5/5: idx_zz_pos_item_diff_temp_new_data...
CREATE INDEX idx_zz_pos_item_diff_temp_new_data
ON zz_pos_item_diff_temp(
    compare_status,
    subscriber_nb,
    cellular_nb,
    invoice_no_nb
)
COMPRESS 1;
PROMPT ✓ 完成

PROMPT
PROMPT ✓ 所有索引创建完成

-- ============================================================================
-- 阶段4：收集统计信息（关键步骤！⚠️⚠️⚠️）
-- ============================================================================
PROMPT
PROMPT ======== 阶段4：收集统计信息（关键步骤，百万数据约需15-30秒）========

BEGIN
    DBMS_OUTPUT.PUT_LINE('开始收集统计信息...');

    DBMS_STATS.GATHER_TABLE_STATS(
        ownname          => 'FES',
        tabname          => 'ZZ_POS_ITEM_DIFF_TEMP',
        cascade          => TRUE,              -- 同时收集索引统计信息 ✅
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt       => 'FOR ALL COLUMNS SIZE AUTO',
        degree           => 4,                 -- 并行度
        granularity      => 'ALL',
        no_invalidate    => FALSE              -- 立即失效旧的游标
    );

    DBMS_OUTPUT.PUT_LINE('✓ 统计信息收集完成');
END;
/

-- ============================================================================
-- 阶段5：验证索引和统计信息
-- ============================================================================
PROMPT
PROMPT ======== 阶段5：验证结果 ========

PROMPT
PROMPT ========== 索引列表 ==========
SELECT
    index_name,
    status,
    index_type,
    compression,
    num_rows,
    leaf_blocks
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
ORDER BY index_name;

PROMPT
PROMPT ========== 统计信息验证 ==========
SELECT
    index_name,
    num_rows,
    distinct_keys,
    TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed,
    CASE
        WHEN last_analyzed IS NULL THEN '❌ 缺失统计信息'
        WHEN last_analyzed < SYSDATE - 1/24 THEN '⚠️ 统计信息较旧'
        ELSE '✓ 统计信息正常'
    END as stats_status
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
ORDER BY index_name;

PROMPT
PROMPT ========== 表统计信息 ==========
SELECT
    table_name,
    num_rows,
    blocks,
    TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed
FROM user_tables
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- ============================================================================
-- 完成总结
-- ============================================================================
PROMPT
PROMPT ======================================================================
PROMPT 重建完成！总结：
PROMPT ======================================================================
PROMPT ✓ 阶段1：已备份旧索引定义
PROMPT ✓ 阶段2：已删除所有旧索引
PROMPT ✓ 阶段3：已创建5个新索引
PROMPT ✓ 阶段4：已收集表和索引统计信息（关键步骤）
PROMPT ✓ 阶段5：已验证索引和统计信息状态
PROMPT
PROMPT 关键检查项：
PROMPT 1. 所有索引的STATUS应为'VALID' ✅
PROMPT 2. 所有索引的last_analyzed应为当前时间 ✅
PROMPT 3. stats_status应为'✓ 统计信息正常' ✅
PROMPT
PROMPT 完成时间：
SELECT TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') as end_time FROM dual;
PROMPT ======================================================================

SET TIMING OFF;
SET ECHO OFF;

