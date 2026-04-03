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
