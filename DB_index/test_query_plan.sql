-- ============================================================================
-- ??????????
-- ??: ????????????????
-- ============================================================================
SET LINESIZE 200;
SET PAGESIZE 1000;
SET SERVEROUTPUT ON;
PROMPT ========================================;
PROMPT ????????;
PROMPT ========================================;
-- ============================================================================
-- ??1: ????1
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ??1: ??1????;
PROMPT ========================================;
PROMPT;
PROMPT ??SQL:;
PROMPT SELECT * FROM zz_pos_item_diff_temp;
PROMPT WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE);
PROMPT   and invoice_no_nb='MVNOs invo';
PROMPT   and compare_status = '2';
PROMPT   and inv_type_nb ='02';
PROMPT ORDER BY compare_status, subscriber, cellular;
PROMPT;
EXPLAIN PLAN FOR
SELECT * FROM zz_pos_item_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
  and invoice_no_nb='MVNOs invo' 
  and compare_status = '2' 
  and inv_type_nb ='02' 
ORDER BY compare_status, subscriber, cellular;
PROMPT ????:;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'ALL'));
-- ============================================================================
-- ??2: ????2
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ??2: ??2????;
PROMPT ========================================;
PROMPT;
PROMPT ??SQL:;
PROMPT SELECT * FROM zz_pos_item_diff_temp;
PROMPT WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE);
PROMPT   and invoice_no_nb='MVNOs invo';
PROMPT ORDER BY compare_status, subscriber, cellular;
PROMPT;
EXPLAIN PLAN FOR
SELECT * FROM zz_pos_item_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
  and invoice_no_nb='MVNOs invo' 
ORDER BY compare_status, subscriber, cellular;
PROMPT ????:;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'ALL'));
-- ============================================================================
-- ??3: ???? idx_pos_diff_new_data????
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ??3: ???? idx_pos_diff_new_data;
PROMPT ========================================;
PROMPT;
EXPLAIN PLAN FOR
SELECT /*+ INDEX(zz_pos_item_diff_temp idx_pos_diff_new_data) */ * 
FROM zz_pos_item_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
  and invoice_no_nb='MVNOs invo' 
  and compare_status = '2' 
  and inv_type_nb ='02' 
ORDER BY compare_status, subscriber, cellular;
PROMPT ????????? idx_pos_diff_new_data?:;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'ALL'));
-- ============================================================================
-- ??4: ???? idx_pos_diff_export_main????
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ??4: ???? idx_pos_diff_export_main;
PROMPT ========================================;
PROMPT;
EXPLAIN PLAN FOR
SELECT /*+ INDEX(zz_pos_item_diff_temp idx_pos_diff_export_main) */ * 
FROM zz_pos_item_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
  and invoice_no_nb='MVNOs invo' 
  and compare_status = '2' 
  and inv_type_nb ='02' 
ORDER BY compare_status, subscriber, cellular;
PROMPT ????????? idx_pos_diff_export_main?:;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'ALL'));
-- ============================================================================
-- ??5: ??????
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ??????;
PROMPT ========================================;
SELECT index_name, column_name, column_position
FROM user_ind_columns
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
ORDER BY index_name, column_position;
-- ============================================================================
-- ??6: ???????????????????
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ??6: ??????????;
PROMPT ========================================;
PROMPT;
PROMPT ???????????????????????;
PROMPT ?????????????;
PROMPT;
/*
SET TIMING ON;
PROMPT ??1????:;
SELECT COUNT(*) FROM zz_pos_item_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
  and invoice_no_nb='MVNOs invo' 
  and compare_status = '2' 
  and inv_type_nb ='02';
PROMPT ??2????:;
SELECT COUNT(*) FROM zz_pos_item_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
  and invoice_no_nb='MVNOs invo';
SET TIMING OFF;
*/
-- ============================================================================
-- ??
-- ============================================================================
PROMPT;
PROMPT ========================================;
PROMPT ?????;
PROMPT ========================================;
PROMPT;
PROMPT ????:;
PROMPT 1. ??1???2???? idx_pos_diff_export_main;
PROMPT 2. idx_pos_diff_new_data ???????????;
PROMPT 3. ????????????? idx_pos_diff_custom_query;
PROMPT;
PROMPT ??????: ????????.md;
PROMPT ???????: ???????????.md;
PROMPT;
