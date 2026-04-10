@echo off
REM ============================================================================
REM 数据导入与统计信息收集一体化脚本（Windows版本）
REM 用途：TRUNCATE + SQL*Loader + 统计信息收集
REM ============================================================================

SETLOCAL EnableDelayedExpansion

REM 配置参数
SET DB_USER=FES
SET DB_PASSWORD=your_password
SET DB_INSTANCE=database
SET TABLE_NAME=ZZ_POS_ITEM_DIFF_TEMP
SET CONTROL_FILE=load_pos_item_diff.ctl
SET DATA_FILE=pos_item_diff.dat

echo ======== 开始数据导入流程 ========
echo 开始时间: %DATE% %TIME%

REM ============================================================================
REM 阶段1：清空表数据
REM ============================================================================
echo.
echo 阶段1: 清空表数据...

sqlplus -S %DB_USER%/%DB_PASSWORD%@%DB_INSTANCE% @- << EOF
SET SERVEROUTPUT ON;
WHENEVER SQLERROR EXIT SQL.SQLCODE;

BEGIN
    EXECUTE IMMEDIATE 'TRUNCATE TABLE %TABLE_NAME%';
    DBMS_OUTPUT.PUT_LINE('√ 表已清空: %TABLE_NAME%');
EXCEPTION
    WHEN OTHERS THEN
        DBMS_OUTPUT.PUT_LINE('× 清空失败: ' || SQLERRM);
        RAISE;
END;
/
EXIT;
EOF

IF %ERRORLEVEL% NEQ 0 (
    echo × 表清空失败，终止流程
    EXIT /B 1
)

REM ============================================================================
REM 阶段2：SQL*Loader导入数据
REM ============================================================================
echo.
echo 阶段2: SQL*Loader导入数据...

SET LOG_FILE=sqlldr_%TABLE_NAME%_%DATE:~0,4%%DATE:~5,2%%DATE:~8,2%_%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%.log
sqlldr userid=%DB_USER%/%DB_PASSWORD%@%DB_INSTANCE% control=%CONTROL_FILE% data=%DATA_FILE% direct=true errors=1000 log=%LOG_FILE%

IF %ERRORLEVEL% NEQ 0 (
    echo × 数据导入失败，终止流程
    EXIT /B 1
)

echo √ 数据导入完成，查看日志: %LOG_FILE%

REM ============================================================================
REM 阶段3：收集统计信息（关键步骤！）
REM ============================================================================
echo.
echo 阶段3: 收集统计信息...

sqlplus -S %DB_USER%/%DB_PASSWORD%@%DB_INSTANCE% @- << EOF
SET SERVEROUTPUT ON;
SET TIMING ON;
WHENEVER SQLERROR EXIT SQL.SQLCODE;

DECLARE
    v_start_time TIMESTAMP := SYSTIMESTAMP;
    v_end_time TIMESTAMP;
    v_num_rows NUMBER;
BEGIN
    -- 收集表和索引统计信息
    DBMS_STATS.GATHER_TABLE_STATS(
        ownname          => '%DB_USER%',
        tabname          => '%TABLE_NAME%',
        cascade          => TRUE,
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt       => 'FOR ALL COLUMNS SIZE AUTO',
        degree           => 4,
        granularity      => 'ALL',
        no_invalidate    => FALSE
    );

    v_end_time := SYSTIMESTAMP;

    -- 验证统计信息
    SELECT num_rows INTO v_num_rows
    FROM user_tables
    WHERE table_name = '%TABLE_NAME%';

    DBMS_OUTPUT.PUT_LINE('√ 统计信息收集完成');
    DBMS_OUTPUT.PUT_LINE('  - 表行数: ' || v_num_rows);
    DBMS_OUTPUT.PUT_LINE('  - 耗时: ' || ROUND(EXTRACT(SECOND FROM (v_end_time - v_start_time)), 2) || ' 秒');

    -- 验证索引统计信息
    FOR idx IN (SELECT index_name, num_rows, last_analyzed
                FROM user_indexes
                WHERE table_name = '%TABLE_NAME%'
                ORDER BY index_name) LOOP
        DBMS_OUTPUT.PUT_LINE('  - 索引: ' || RPAD(idx.index_name, 30) ||
                           ' 行数: ' || LPAD(TO_CHAR(idx.num_rows), 10) ||
                           ' 时间: ' || TO_CHAR(idx.last_analyzed, 'YYYY-MM-DD HH24:MI:SS'));
    END LOOP;
END;
/

PROMPT
PROMPT ======== 数据质量验证 ========
SELECT
    '总行数' as metric,
    TO_CHAR(COUNT(*), '999,999,999') as value
FROM %TABLE_NAME%
UNION ALL
SELECT
    'compare_status=1' as metric,
    TO_CHAR(COUNT(*), '999,999,999') as value
FROM %TABLE_NAME%
WHERE compare_status = '1'
UNION ALL
SELECT
    'compare_status=2' as metric,
    TO_CHAR(COUNT(*), '999,999,999') as value
FROM %TABLE_NAME%
WHERE compare_status = '2'
UNION ALL
SELECT
    '今日数据' as metric,
    TO_CHAR(COUNT(*), '999,999,999') as value
FROM %TABLE_NAME%
WHERE TRUNC(create_date) = TRUNC(SYSDATE);

EXIT;
EOF

IF %ERRORLEVEL% NEQ 0 (
    echo × 统计信息收集失败
    EXIT /B 1
)

REM ============================================================================
REM 阶段4：验证执行计划
REM ============================================================================
echo.
echo 阶段4: 验证执行计划...

sqlplus -S %DB_USER%/%DB_PASSWORD%@%DB_INSTANCE% @- << EOF
SET LINESIZE 200;
SET PAGESIZE 1000;

EXPLAIN PLAN FOR
SELECT * FROM %TABLE_NAME%
WHERE TRUNC(create_date) = TRUNC(SYSDATE)
  AND compare_status = '1'
  AND subscriber_nb = 'TEST123'
ORDER BY compare_status, cellular_nb, subscriber_nb;

PROMPT ======== 执行计划验证 ========
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format=>'BASIC +COST +PREDICATE'));

PROMPT
PROMPT ======== 统计信息状态 ========
SELECT
    table_name,
    num_rows,
    TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed,
    CASE
        WHEN last_analyzed IS NULL THEN '× 缺失统计信息'
        WHEN last_analyzed < SYSDATE - 1/24 THEN '! 统计信息较旧'
        ELSE '√ 统计信息正常'
    END as status
FROM user_tables
WHERE table_name = '%TABLE_NAME%';

EXIT;
EOF

echo.
echo ======== 数据导入流程完成 ========
echo 完成时间: %DATE% %TIME%
echo.
echo 关键检查项：
echo 1. 统计信息已收集（last_analyzed为当前时间）
echo 2. 执行计划使用索引（不是FULL TABLE SCAN）
echo 3. 数据行数匹配预期
echo 4. 所有索引的last_analyzed为当前时间
echo.

ENDLOCAL

