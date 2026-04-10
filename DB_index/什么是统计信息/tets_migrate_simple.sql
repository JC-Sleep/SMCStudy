-- 简化版迁移脚本
-- 快速删除旧索引并创建新索引

-- 删除旧索引
DROP INDEX FES.IDX_ZZ_PSUB_REF_DIFF_TEMP_STAT;
DROP INDEX FES.IDX_POS_DIFF_TEMP_DATE;
DROP INDEX FES.IDX_POS_DIFF_TEMP_STAT;

-- 创建新索引 - ZZ_PSUB_REF_diff_temp
CREATE INDEX idx_diff_temp_trunc_date ON ZZ_PSUB_REF_diff_temp(TRUNC(create_date));
CREATE INDEX idx_diff_temp_export_main ON ZZ_PSUB_REF_diff_temp(TRUNC(create_date), compare_status, Subscriber, Cellular);
CREATE INDEX idx_diff_temp_status ON ZZ_PSUB_REF_diff_temp(compare_status);
CREATE INDEX idx_diff_temp_old_data ON ZZ_PSUB_REF_diff_temp(compare_status, Subscriber, Cellular) COMPRESS 1;
CREATE INDEX idx_diff_temp_new_data ON ZZ_PSUB_REF_diff_temp(compare_status, Subscriber_NB, Cellular_NB) COMPRESS 1;

-- 创建新索引 - zz_pos_item_diff_temp
CREATE INDEX idx_pos_diff_trunc_date ON zz_pos_item_diff_temp(TRUNC(create_date));
CREATE INDEX idx_pos_diff_export_main ON zz_pos_item_diff_temp(TRUNC(create_date), compare_status, subscriber, cellular, invoice_no);
CREATE INDEX idx_pos_diff_status ON zz_pos_item_diff_temp(compare_status);
CREATE INDEX idx_pos_diff_old_data ON zz_pos_item_diff_temp(compare_status, subscriber, cellular, invoice_no) COMPRESS 1;
CREATE INDEX idx_pos_diff_new_data ON zz_pos_item_diff_temp(compare_status, subscriber_nb, cellular_nb, invoice_no_nb) COMPRESS 1;

-- 收集统计信息
EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'ZZ_PSUB_REF_DIFF_TEMP', cascade => TRUE);
EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'ZZ_POS_ITEM_DIFF_TEMP', cascade => TRUE);


-----------test
EXPLAIN PLAN FOR SELECT * FROM ZZ_PSUB_REF_diff_temp WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) ORDER BY compare_status, subscriber, cellular;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);
-- 预期: INDEX RANGE SCAN | IDX_DIFF_TEMP_EXPORT_MAIN

EXPLAIN PLAN FOR SELECT * FROM zz_pos_item_diff_temp WHERE  invoice_no_nb='MVNOs invo'  ORDER BY subscriber, cellular;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);


select * from ZZ_PSUB_REF_diff_temp order by create_date desc;
select count(*) from ZZ_PSUB_REF_diff_temp;  ---- 441772

select * from zz_pos_item_diff_temp order by create_date desc;
select count(*) from zz_pos_item_diff_temp ;  ----467

select * FROM  ZZ_PSUB_REF_nb;
select * FROM zz_pos_item_nb  where system_ind='B';
select count(*) FROM  ZZ_PSUB_REF_nb;   ---328
select count(*) FROM zz_pos_item_nb;   ---331
select count(*) FROM zz_pos_item_nb where system_ind='B';  ---331

select count(*) FROM  ZZ_PSUB_REF;   ---2622
select count(*) FROM zz_pos_item;   ---973
select count(*) FROM zz_pos_item where system_ind='B';  ---973

