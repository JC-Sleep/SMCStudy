-- ============================================================================
-- 快速修复脚本 - 删除有问题的索引并重建
-- 用途: 如果之前创建索引失败，使用此脚本快速修复
-- ============================================================================

SET ECHO ON;
SET FEEDBACK ON;
SPOOL index_fix.log;

PROMPT ========================================
PROMPT 开始修复索引
PROMPT 当前时间:
SELECT TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') FROM DUAL;
PROMPT ========================================

-- ============================================================================
-- 删除可能存在的问题索引（忽略错误）
-- ============================================================================

-- ZZ_PSUB_REF_diff_temp 表的问题索引
PROMPT 清理 ZZ_PSUB_REF_diff_temp 的旧索引...
BEGIN
    FOR idx IN (
        SELECT index_name FROM user_indexes
        WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP'
        AND index_name NOT LIKE 'SYS_%'
    ) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
            DBMS_OUTPUT.PUT_LINE('删除: ' || idx.index_name);
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
    END LOOP;
END;
/

-- zz_pos_item_diff_temp 表的问题索引
PROMPT 清理 zz_pos_item_diff_temp 的旧索引...
BEGIN
    FOR idx IN (
        SELECT index_name FROM user_indexes
        WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
        AND index_name NOT LIKE 'SYS_%'
    ) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
            DBMS_OUTPUT.PUT_LINE('删除: ' || idx.index_name);
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
    END LOOP;
END;
/

-- ============================================================================
-- 为 ZZ_PSUB_REF_diff_temp 创建新索引
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 创建 ZZ_PSUB_REF_diff_temp 索引
PROMPT ========================================

-- 索引1: 函数索引
PROMPT 创建 idx_diff_temp_trunc_date ...
CREATE INDEX idx_diff_temp_trunc_date ON ZZ_PSUB_REF_diff_temp(TRUNC(create_date));

-- 索引2: 复合索引（主查询）
PROMPT 创建 idx_diff_temp_export_main ...
CREATE INDEX idx_diff_temp_export_main ON ZZ_PSUB_REF_diff_temp(
    TRUNC(create_date),
    compare_status,
    Subscriber,
    Cellular
);

-- 索引3: 状态索引
PROMPT 创建 idx_diff_temp_status ...
CREATE INDEX idx_diff_temp_status ON ZZ_PSUB_REF_diff_temp(compare_status);

-- 索引4: 旧系统数据索引（带压缩）
PROMPT 创建 idx_diff_temp_old_data ...
CREATE INDEX idx_diff_temp_old_data ON ZZ_PSUB_REF_diff_temp(
    compare_status,
    Subscriber,
    Cellular
) COMPRESS 1;

-- 索引5: 新系统数据索引（带压缩）
PROMPT 创建 idx_diff_temp_new_data ...
CREATE INDEX idx_diff_temp_new_data ON ZZ_PSUB_REF_diff_temp(
    compare_status,
    Subscriber_NB,
    Cellular_NB
) COMPRESS 1;

PROMPT ✓ ZZ_PSUB_REF_diff_temp 索引创建完成

-- ============================================================================
-- 为 zz_pos_item_diff_temp 创建新索引
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 创建 zz_pos_item_diff_temp 索引
PROMPT ========================================

-- 索引1: 函数索引
PROMPT 创建 idx_pos_diff_trunc_date ...
CREATE INDEX idx_pos_diff_trunc_date ON zz_pos_item_diff_temp(TRUNC(create_date));

-- 索引2: 复合索引（主查询）
PROMPT 创建 idx_pos_diff_export_main ...
CREATE INDEX idx_pos_diff_export_main ON zz_pos_item_diff_temp(
    TRUNC(create_date),
    compare_status,
    subscriber,
    cellular,
    invoice_no
);

-- 索引3: 状态索引
PROMPT 创建 idx_pos_diff_status ...
CREATE INDEX idx_pos_diff_status ON zz_pos_item_diff_temp(compare_status);

-- 索引4: 旧系统数据索引（带压缩）
PROMPT 创建 idx_pos_diff_old_data ...
CREATE INDEX idx_pos_diff_old_data ON zz_pos_item_diff_temp(
    compare_status,
    subscriber,
    cellular,
    invoice_no
) COMPRESS 1;

-- 索引5: 新系统数据索引（带压缩）
PROMPT 创建 idx_pos_diff_new_data ...
CREATE INDEX idx_pos_diff_new_data ON zz_pos_item_diff_temp(
    compare_status,
    subscriber_nb,
    cellular_nb,
    invoice_no_nb
) COMPRESS 1;

PROMPT ✓ zz_pos_item_diff_temp 索引创建完成

-- ============================================================================
-- 收集统计信息
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 收集统计信息
PROMPT ========================================

EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'ZZ_PSUB_REF_DIFF_TEMP', cascade => TRUE);
PROMPT ✓ ZZ_PSUB_REF_diff_temp 统计信息收集完成

EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'ZZ_POS_ITEM_DIFF_TEMP', cascade => TRUE);
PROMPT ✓ zz_pos_item_diff_temp 统计信息收集完成

-- ============================================================================
-- 验证结果
-- ============================================================================
PROMPT
PROMPT ========================================
PROMPT 验证索引创建结果
PROMPT ========================================

PROMPT
PROMPT ZZ_PSUB_REF_diff_temp 索引:
SELECT index_name, status, index_type, compression
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP'
ORDER BY index_name;

PROMPT
PROMPT zz_pos_item_diff_temp 索引:
SELECT index_name, status, index_type, compression
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
ORDER BY index_name;

PROMPT
PROMPT ========================================
PROMPT ✓ 修复完成！
PROMPT ========================================
PROMPT
PROMPT 预期结果:
PROMPT - ZZ_PSUB_REF_diff_temp: 5个索引
PROMPT - zz_pos_item_diff_temp: 5个索引
PROMPT - 所有索引状态应为 VALID
PROMPT - 包含压缩的索引应显示 ENABLED
PROMPT
SELECT TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') AS 完成时间 FROM DUAL;

SPOOL OFF;
SET ECHO OFF;

PROMPT 日志已保存到: index_fix.log

