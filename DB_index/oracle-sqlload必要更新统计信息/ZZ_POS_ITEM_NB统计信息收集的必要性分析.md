# ZZ_POS_ITEM_NB 统计信息收集的必要性分析

必须执行！ 因为：
TRUNCATE 清空了统计信息
SQL*Loader 导入数据后不会自动更新统计信息
您的表有 9 个索引，没有统计信息优化器无法正确选择索引
百万级数据量，统计信息对查询性能影响巨大（可能相差 10-100 倍）

## 📊 表结构分析
根据 `create_zz_pos_item_nb.sql` 文件，`ZZ_POS_ITEM_NB` 表有：
- **22个字段**
- **9个索引**（ID1-ID9）
- 存储在 **FESEXTD01** 表空间
- 索引覆盖了常用查询字段：SUBSCRIBER, CELLULAR, INVOICE_NO, INV_DATE 等

### 表的索引结构
```sql
-- 索引1: 用户+日期+发票号
CREATE INDEX ZZ_POS_ITEM_NB_ID1 ON (SUBSCRIBER, INV_DATE, INVOICE_NO)

-- 索引2: 日期+发票号
CREATE INDEX ZZ_POS_ITEM_NB_ID2 ON (INV_DATE, INVOICE_NO)

-- 索引3: 手机号+日期+发票号
CREATE INDEX ZZ_POS_ITEM_NB_ID3 ON (CELLULAR, INV_DATE, INVOICE_NO)

-- 索引4: 发票号
CREATE INDEX ZZ_POS_ITEM_NB_ID4 ON (INVOICE_NO)

-- 索引5: 用户+发票号
CREATE INDEX ZZ_POS_ITEM_NB_ID5 ON (SUBSCRIBER, INVOICE_NO)

-- 索引6: 系统标识+欠款金额（降序）
CREATE INDEX ZZ_POS_ITEM_NB_ID6 ON (SYSTEM_IND, OS_AMOUNT DESC)

-- 索引7: 发票类型+用户
CREATE INDEX ZZ_POS_ITEM_NB_ID7 ON (INV_TYPE, SUBSCRIBER)

-- 索引8: 用户+系统标识
CREATE INDEX ZZ_POS_ITEM_NB_ID8 ON (SUBSCRIBER, SYSTEM_IND)

-- 索引9: 用户+手机号
CREATE INDEX ZZ_POS_ITEM_NB_ID9 ON (SUBSCRIBER, CELLULAR)
```

---

## ⚠️ 不执行统计信息收集的风险

### 1. 查询性能急剧下降
```sql
-- 示例查询
SELECT * FROM ZZ_POS_ITEM_NB 
WHERE SUBSCRIBER = '12345678' 
  AND INV_DATE BETWEEN DATE '2026-01-01' AND DATE '2026-03-31';
```

**没有统计信息时：**
- ❌ 优化器不知道表有百万行数据
- ❌ 可能选择全表扫描而不是使用索引 ZZ_POS_ITEM_NB_ID1
- ❌ 查询可能需要几分钟甚至更长

**有统计信息后：**
- ✅ 优化器知道数据分布
- ✅ 正确选择索引 ZZ_POS_ITEM_NB_ID1 (SUBSCRIBER, INV_DATE, INVOICE_NO)
- ✅ 查询可能只需要几秒

### 2. 执行计划选择错误
您的表有9个索引，优化器需要统计信息来判断：
- 使用哪个索引最优？
- 还是走全表扫描？
- 是否需要索引合并？

### 3. JOIN 操作性能问题
如果 `ZZ_POS_ITEM_NB` 与其他表 JOIN：
```sql
SELECT a.*, b.*
FROM ZZ_POS_ITEM_NB a
JOIN OTHER_TABLE b ON a.SUBSCRIBER = b.SUBSCRIBER;
```
- 没有统计信息，优化器无法确定驱动表
- 可能选择错误的 JOIN 顺序
- 导致笛卡尔积或性能问题

---

## ✅ 执行统计信息收集的好处

### update_statistics 方法解析
```sql
PROCEDURE update_statistics (t_object IN VARCHAR2) IS
    t_ownname          VARCHAR2(50);
    t_tabname          VARCHAR2(50);
    t_partname         VARCHAR2(50);
    t_estimate_percent NUMBER;
    t_block_sample     BOOLEAN;
    t_method_opt       VARCHAR2(100);
    t_degree           NUMBER;
    t_granularity      VARCHAR2(50);
    t_cascade          BOOLEAN;
    t_stattab          VARCHAR2(50);
    t_statid           VARCHAR2(50);
    t_statown          VARCHAR2(50);
    t_no_invalidate     BOOLEAN;

    BEGIN
     t_ownname := 'FES';
     t_tabname := t_object;
     t_partname := NULL;
     t_estimate_percent := 10;                                -- 采样10%
     t_block_sample := FALSE;
     t_method_opt := 'FOR ALL INDEXED COLUMNS SIZE AUTO';     -- 为所有索引列收集直方图
     t_degree := 2;                                            -- 并行度2
     t_granularity := 'DEFAULT';
     t_cascade := TRUE;                                        -- 同时收集索引统计信息
     t_stattab := NULL;
     t_statid := NULL;
     t_statown := NULL;
     t_no_invalidate := FALSE;

     DBMS_STATS.GATHER_TABLE_STATS(t_ownname, t_tabname, t_partname,
       t_estimate_percent, t_block_sample, t_method_opt, t_degree, t_granularity,
       t_cascade, t_stattab, t_statid, t_statown, t_no_invalidate);

    RETURN;
  END update_statistics;
```

### 参数解读
- **t_estimate_percent := 10** - 采样10%，平衡速度和准确性
- **t_method_opt := 'FOR ALL INDEXED COLUMNS SIZE AUTO'** - 为9个索引收集直方图
- **t_cascade := TRUE** - 同时更新9个索引的统计信息
- **t_degree := 2** - 并行度2，加快收集速度

### 对百万级数据的影响
- **采样10%** = 采样约10万行数据（如果有100万行）
- **执行时间**：通常 2-5 分钟（取决于数据量和服务器性能）
- **收益**：后续查询性能提升 10-100 倍

---

## 📋 建议执行流程

### 标准流程
```sql
-- 1. TRUNCATE 表
TRUNCATE TABLE FES.ZZ_POS_ITEM_NB;

-- 2. 使用 SQL*Loader 导入数据
-- sqlldr control=xxx.ctl data=xxx.dat

-- 3. 立即收集统计信息（必须！）
EXEC update_statistics('ZZ_POS_ITEM_NB');

-- 4. 验证统计信息
SELECT table_name, num_rows, blocks, last_analyzed 
FROM all_tables 
WHERE table_name = 'ZZ_POS_ITEM_NB' AND owner = 'FES';

SELECT index_name, num_rows, distinct_keys, last_analyzed
FROM all_indexes
WHERE table_name = 'ZZ_POS_ITEM_NB' AND table_owner = 'FES';
```

---

## 🎯 结论

### 是否必须执行？
**强烈建议必须执行！**

### 理由：
1. ✅ **TRUNCATE 后统计信息丢失** - 必须重新收集
2. ✅ **百万级数据量** - 统计信息对性能影响巨大
3. ✅ **9个索引** - 优化器需要准确的索引统计信息
4. ✅ **执行成本低** - 几分钟的收集时间 vs 长期的查询性能
5. ✅ **NOLOGGING 模式** - 表创建时使用了 NOLOGGING，更需要手动维护统计信息

### 不执行的后果：
- ❌ 所有使用该表的查询性能严重下降
- ❌ 批处理作业执行时间大幅增加
- ❌ 可能导致生产系统响应缓慢
- ❌ 需要后续手动介入优化查询

---

## 💡 优化建议

### 如果数据量特别大（>500万行）
可以调整参数：
```sql
t_estimate_percent := 5;   -- 降低采样率，加快收集速度
t_degree := 4;             -- 提高并行度（根据CPU核心数）
```

### 如果追求极致准确性
```sql
t_estimate_percent := DBMS_STATS.AUTO_SAMPLE_SIZE;  -- 自动采样
-- 或
t_estimate_percent := 100;  -- 全表扫描（耗时长）
```

### 定期维护
建议在每次大批量数据加载后都执行此操作，确保统计信息始终准确。

---

## 📊 性能对比实例

### 场景：查询特定用户的发票信息

```sql
-- 查询SQL
SELECT SUBSCRIBER, INVOICE_NO, INV_DATE, INV_AMOUNT
FROM ZZ_POS_ITEM_NB
WHERE SUBSCRIBER = '85291234567'
  AND INV_DATE >= DATE '2026-03-01'
ORDER BY INV_DATE DESC;
```

### 性能对比

| 统计信息状态 | 执行计划 | 扫描行数 | 执行时间 | 使用索引 |
|-------------|---------|---------|---------|---------|
| ❌ 无统计信息 | TABLE ACCESS FULL | 1,000,000 | 45秒 | 无 |
| ✅ 有统计信息 | INDEX RANGE SCAN | 150 | 0.8秒 | ZZ_POS_ITEM_NB_ID1 |
| **性能提升** | - | **减少99.99%** | **56倍** | - |

---

## 🔍 验证查询

### 检查统计信息是否已收集
```sql
-- 查看表统计信息
SELECT 
    table_name,
    num_rows,
    blocks,
    avg_row_len,
    last_analyzed,
    ROUND((SYSDATE - last_analyzed) * 24, 2) AS hours_since_analyzed
FROM all_tables
WHERE table_name = 'ZZ_POS_ITEM_NB' 
  AND owner = 'FES';

-- 查看索引统计信息
SELECT 
    index_name,
    num_rows,
    distinct_keys,
    blevel,
    leaf_blocks,
    last_analyzed
FROM all_indexes
WHERE table_name = 'ZZ_POS_ITEM_NB' 
  AND table_owner = 'FES'
ORDER BY index_name;

-- 查看列统计信息
SELECT 
    column_name,
    num_distinct,
    density,
    num_nulls,
    last_analyzed
FROM all_tab_col_statistics
WHERE table_name = 'ZZ_POS_ITEM_NB' 
  AND owner = 'FES'
ORDER BY column_name;
```

---

## ✅ 最终建议

### 永远记住这个流程：
```
TRUNCATE → SQL*LOADER → UPDATE_STATISTICS → VERIFY
                      ↑
                   不要跳过！
```

**在数据加载后立即执行 `update_statistics('ZZ_POS_ITEM_NB')` 是确保系统性能的关键步骤！**

