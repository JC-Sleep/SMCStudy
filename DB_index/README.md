# 索引修复快速参考指南

## 🚀 快速执行步骤（3分钟完成）

### 方式1: 使用快速修复脚本（推荐）

```bash
# 1. 连接到Oracle数据库
sqlplus fes/password@database

# 2. 执行快速修复脚本（自动清理旧索引+创建新索引）
@quick_fix_indexes.sql

# 3. 完成！查看日志确认
-- 日志文件: index_fix.log
```

---

### 方式2: 手动执行（如需精细控制）

```bash
# 1. 连接到Oracle数据库
sqlplus fes/password@database

# 2. 执行建表和索引脚本
@create_ZZ_PSUB_REF_DIFF_temp.sql
@create_zz_pos_item_diff_temp.sql

# 3. 验证索引
@verify_indexes.sql
```

---

## ✅ 验证索引创建成功

### 快速检查命令

```sql
-- 查看两个表的索引（应该各有5个）
SELECT table_name, COUNT(*) as index_count
FROM user_indexes
WHERE table_name IN ('ZZ_PSUB_REF_DIFF_TEMP', 'ZZ_POS_ITEM_DIFF_TEMP')
GROUP BY table_name;

-- 预期结果:
-- ZZ_PSUB_REF_DIFF_TEMP    | 5
-- ZZ_POS_ITEM_DIFF_TEMP    | 5
```

### 查看索引详情

```sql
-- 查看索引列表
SELECT index_name, status, index_type
FROM user_indexes
WHERE table_name IN ('ZZ_PSUB_REF_DIFF_TEMP', 'ZZ_POS_ITEM_DIFF_TEMP')
ORDER BY table_name, index_name;

-- 预期结果（所有索引状态应为VALID）:
-- IDX_DIFF_TEMP_EXPORT_MAIN    | VALID | FUNCTION-BASED NORMAL
-- IDX_DIFF_TEMP_NEW_DATA       | VALID | NORMAL
-- IDX_DIFF_TEMP_OLD_DATA       | VALID | NORMAL
-- IDX_DIFF_TEMP_STATUS         | VALID | NORMAL
-- IDX_DIFF_TEMP_TRUNC_DATE     | VALID | FUNCTION-BASED NORMAL
-- IDX_POS_DIFF_EXPORT_MAIN     | VALID | FUNCTION-BASED NORMAL
-- IDX_POS_DIFF_NEW_DATA        | VALID | NORMAL
-- IDX_POS_DIFF_OLD_DATA        | VALID | NORMAL
-- IDX_POS_DIFF_STATUS          | VALID | NORMAL
-- IDX_POS_DIFF_TRUNC_DATE      | VALID | FUNCTION-BASED NORMAL
```

---

## 📂 文件清单

| 文件名 | 用途 | 优先级 |
|--------|------|--------|
| `create_ZZ_PSUB_REF_DIFF_temp.sql` | 建表+索引 (已优化) | ⭐⭐⭐ |
| `create_zz_pos_item_diff_temp.sql` | 建表+索引 (已优化) | ⭐⭐⭐ |
| `quick_fix_indexes.sql` | 快速修复脚本 | ⭐⭐⭐ |
| `verify_indexes.sql` | 验证脚本 | ⭐⭐ |
| `索引优化说明.md` | 详细文档 | ⭐ |
| `索引对比分析.md` | 优化前后对比 | ⭐ |

---

## ❓ 常见问题

### Q1: 如果索引还是创建失败怎么办？

```sql
-- 1. 查看错误信息
SELECT * FROM user_errors WHERE type = 'INDEX';

-- 2. 检查权限
SELECT privilege FROM user_sys_privs WHERE privilege LIKE '%INDEX%';

-- 3. 检查表空间
SELECT tablespace_name, bytes/1024/1024 AS free_mb 
FROM user_free_space 
WHERE tablespace_name = (SELECT tablespace_name FROM user_tables WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP');
```

**常见原因:**
- ❌ 权限不足 → 需要 `CREATE INDEX` 权限
- ❌ 表空间不足 → 需要扩展表空间
- ❌ 表不存在 → 先执行建表语句

---

### Q2: 如何删除所有旧索引？

```sql
-- 删除 ZZ_PSUB_REF_diff_temp 的所有索引
BEGIN
    FOR idx IN (SELECT index_name FROM user_indexes WHERE table_name = 'ZZ_PSUB_REF_DIFF_TEMP') LOOP
        EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
    END LOOP;
END;
/

-- 删除 zz_pos_item_diff_temp 的所有索引
BEGIN
    FOR idx IN (SELECT index_name FROM user_indexes WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP') LOOP
        EXECUTE IMMEDIATE 'DROP INDEX ' || idx.index_name;
    END LOOP;
END;
/
```

---

### Q3: 索引是否正在被使用？

```sql
-- 1. 开启索引监控
ALTER INDEX idx_diff_temp_export_main MONITORING USAGE;
ALTER INDEX idx_pos_diff_export_main MONITORING USAGE;

-- 2. 运行Java程序（bRptCustproDiffComparsion.java 和 bRptOsinvDiffComparison.java）

-- 3. 查看使用情况
SELECT index_name, used, start_monitoring 
FROM v$object_usage 
WHERE index_name LIKE '%DIFF%';
```

---

### Q4: 如何查看执行计划？

```sql
-- 对于 ZZ_PSUB_REF_diff_temp
EXPLAIN PLAN FOR
SELECT * FROM ZZ_PSUB_REF_diff_temp 
WHERE TRUNC(CREATE_DATE) = TRUNC(SYSDATE) 
ORDER BY compare_status, subscriber, cellular;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

-- 预期结果应该包含:
-- INDEX RANGE SCAN | IDX_DIFF_TEMP_EXPORT_MAIN
```

---

## 🎯 索引设计原则总结

### ✅ DO（应该做的）

1. **使用函数索引优化 TRUNC(date)**
   ```sql
   CREATE INDEX idx ON table(TRUNC(date_column));
   ```

2. **使用复合索引覆盖 WHERE + ORDER BY**
   ```sql
   CREATE INDEX idx ON table(where_col, order_col1, order_col2);
   ```

3. **使用 COMPRESS 压缩重复率高的列**
   ```sql
   CREATE INDEX idx ON table(status, other_col) COMPRESS 1;
   ```

---

### ❌ DON'T（不要做的）

1. **不要使用 WHERE 子句（Oracle不支持）**
   ```sql
   -- ❌ 错误
   CREATE INDEX idx ON table(col) WHERE status = '1';
   ```

2. **不要在复合索引中使用 NVL 多列**
   ```sql
   -- ❌ 错误
   CREATE INDEX idx ON table(NVL(col1, col2));
   ```

3. **不要使用 CASE 表达式在普通索引中**
   ```sql
   -- ❌ 错误
   CREATE INDEX idx ON table(CASE WHEN status='1' THEN col END);
   ```

---

## 📊 性能指标

### 优化后预期性能

| 指标 | 优化前 | 优化后 | 目标 |
|------|--------|--------|------|
| 主查询响应时间 | ~15秒 | ~0.1秒 | <1秒 ✅ |
| 索引创建成功率 | 40% | 100% | 100% ✅ |
| 索引空间占用 | - | -50% | 节省空间 ✅ |
| 查询执行计划 | FULL SCAN | INDEX SCAN | INDEX SCAN ✅ |

---

## 🔧 维护建议

### 每周维护（自动化）

```sql
-- 收集统计信息
BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(ownname => 'FES', tabname => 'ZZ_PSUB_REF_DIFF_TEMP', cascade => TRUE);
    DBMS_STATS.GATHER_TABLE_STATS(ownname => 'FES', tabname => 'ZZ_POS_ITEM_DIFF_TEMP', cascade => TRUE);
END;
/
```

### 每月维护（手动）

```sql
-- 重建索引（在线，不影响业务）
ALTER INDEX idx_diff_temp_export_main REBUILD ONLINE;
ALTER INDEX idx_pos_diff_export_main REBUILD ONLINE;

-- 或者合并索引（更快，适用于碎片不严重）
ALTER INDEX idx_diff_temp_export_main COALESCE;
ALTER INDEX idx_pos_diff_export_main COALESCE;
```

### 每季度检查（手动）

```sql
-- 查看索引碎片率
SELECT index_name, 
       blevel AS 索引深度,
       leaf_blocks AS 叶子块数,
       ROUND((del_lf_rows / NULLIF(lf_rows, 0)) * 100, 2) AS 碎片率
FROM user_ind_statistics
WHERE table_name IN ('ZZ_PSUB_REF_DIFF_TEMP', 'ZZ_POS_ITEM_DIFF_TEMP');

-- 如果碎片率 > 20%，考虑重建索引
```

---

## 📞 技术支持

### 检查清单

- [ ] 索引创建成功（10个索引，全部VALID）
- [ ] 执行计划使用索引（INDEX RANGE SCAN）
- [ ] Java程序运行正常
- [ ] 导出CSV文件正常生成
- [ ] 查询响应时间<1秒

### 如遇问题

1. **查看日志**: `index_fix.log` 或 `verify_indexes.log`
2. **检查错误**: `SELECT * FROM user_errors;`
3. **查看文档**: `索引优化说明.md`
4. **对比分析**: `索引对比分析.md`

---

**最后更新**: 2026-04-02  
**版本**: 1.0  
**作者**: GitHub Copilot

