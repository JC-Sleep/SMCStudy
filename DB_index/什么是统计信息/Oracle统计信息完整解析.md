# Oracle统计信息完整解析 - 从原理到实践

## 📚 核心概念：统计信息到底是什么？

### 简单类比
```
统计信息 ≈ 书的目录和索引
实际数据 ≈ 书的正文内容
优化器   ≈ 读者

没有目录的书：读者需要翻遍整本书才能找到内容（全表扫描）
有目录的书：读者可以快速定位到需要的章节（索引扫描）
```

### 统计信息 ≠ 索引

| 对比项 | 索引 (Index) | 统计信息 (Statistics) |
|--------|-------------|---------------------|
| **本质** | 实际的数据结构（B树） | 描述数据的元数据 |
| **存储位置** | 磁盘上的真实文件 | 数据字典表 |
| **作用** | 加速数据检索 | 帮助优化器选择执行计划 |
| **大小** | 可能几百MB | 只有几KB |
| **影响** | 直接影响查询性能 | 间接影响（通过优化器） |

---

## 🎯 统计信息包含什么？

### 1. 表统计信息
```sql
SELECT 
    table_name,
    num_rows,          -- 总行数（例如：1,000,000）
    blocks,            -- 占用的数据块数（例如：15,000）
    avg_row_len,       -- 平均行长度（字节，例如：120）
    last_analyzed,     -- 最后分析时间
    stale_stats        -- 是否过期（YES/NO）
FROM user_tables
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
```

**示例输出：**
```
TABLE_NAME              NUM_ROWS  BLOCKS  AVG_ROW_LEN  LAST_ANALYZED        STALE_STATS
----------------------- --------  ------  -----------  -------------------  -----------
ZZ_POS_ITEM_DIFF_TEMP   1000000   15000   120          2026-04-07 10:30:00  NO
```

### 2. 索引统计信息
```sql
SELECT 
    index_name,
    num_rows,          -- 索引条目数
    distinct_keys,     -- 唯一键数量（选择性指标）
    clustering_factor, -- 聚集因子（数据物理排序程度，越小越好）
    blevel,            -- B树深度（访问开销，通常2-4）
    leaf_blocks        -- 叶子块数量
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
```

**关键指标解读：**
- `distinct_keys` 越大 → 选择性越好 → 索引越有效
- `clustering_factor` 接近blocks → 数据有序，索引效率高
- `clustering_factor` 接近num_rows → 数据无序，索引效率低
- `blevel` > 4 → 索引碎片化严重，需要REBUILD

### 3. 列统计信息
```sql
SELECT 
    column_name,
    num_distinct,      -- 唯一值数量（选择性）
    density,           -- 密度（1/num_distinct）
    num_nulls,         -- NULL值数量
    histogram,         -- 直方图类型（数据分布）
    num_buckets        -- 直方图桶数
FROM user_tab_col_statistics
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
  AND column_name IN ('COMPARE_STATUS', 'SUBSCRIBER_NB');
```

**直方图类型：**
- `NONE` - 无直方图（假设数据均匀分布）
- `FREQUENCY` - 频率直方图（每个唯一值一个桶）
- `HEIGHT BALANCED` - 高度平衡直方图（数据倾斜）

---

## ⚙️ 自动统计信息收集机制

### Oracle如何自动收集统计信息？

```
┌──────────────────────────────────────────────────────────────┐
│         Oracle自动统计信息收集机制（11g+版本）               │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  触发条件：表数据变化 > 10%                                  │
│  检测方式：通过 DML 操作监控（INSERT/UPDATE/DELETE）        │
│  执行时间：默认在维护窗口（晚上10点-凌晨6点）               │
│                                                              │
│  ✅ 会自动收集的场景：                                       │
│    1. 常规 INSERT/UPDATE/DELETE（累计变化>10%）             │
│    2. CTAS (CREATE TABLE AS SELECT)                          │
│    3. 维护窗口内自动任务                                     │
│                                                              │
│  ❌ 不会自动收集的场景（重要！）：                           │
│    1. TRUNCATE（DDL操作，不记录DML）                        │
│    2. SQL*Loader直接路径加载（绕过DML监控）                 │
│    3. TRUNCATE + SQL*Loader（两者都不触发）                 │
│    4. DROP + CREATE INDEX（新建索引无统计信息）             │
│    5. IMPDP（数据泵导入，除非使用特定参数）                 │
│    6. ALTER INDEX REBUILD（重建索引）                        │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 为什么TRUNCATE不会自动收集？

#### **TRUNCATE vs DELETE性能对比：**
```sql
-- 场景：清空100万行数据

-- 方式1：DELETE（DML操作）
DELETE FROM zz_pos_item_diff_temp;
-- 耗时：30秒
-- 记录Redo Log：是
-- 触发统计信息更新：是 ✅
-- 可回滚：是

-- 方式2：TRUNCATE（DDL操作）
TRUNCATE TABLE zz_pos_item_diff_temp;
-- 耗时：0.5秒 🚀（快60倍）
-- 记录Redo Log：否
-- 触发统计信息更新：否 ❌
-- 可回滚：否
```

**Oracle的设计权衡：**
- **性能优先**：TRUNCATE快速清空表，不记录日志
- **代价**：不自动更新统计信息，留给用户手动收集

---

### 为什么SQL*Loader不会自动收集？

#### **SQL*Loader两种模式：**

**模式1：常规路径（DIRECT=FALSE）**
```bash
sqlldr userid=FES/password control=load.ctl direct=false
```
```
工作流程：
应用 → SQL引擎 → Buffer Cache → Redo Log → 数据文件
                                          ↓
                                 （触发DML监控）
                                          ↓
                              （可能自动收集统计信息）

速度：100万行约10分钟
自动统计信息：可能（如果变化>10%且在维护窗口）
```

**模式2：直接路径（DIRECT=TRUE，推荐）**
```bash
sqlldr userid=FES/password control=load.ctl direct=true
```
```
工作流程：
应用 → 直接写入数据文件（绕过SQL引擎和Buffer Cache）
           ↓
   （不触发DML监控）❌
           ↓
   （不会自动收集统计信息）❌

速度：100万行约1分钟 🚀（快10倍）
自动统计信息：不会
```

**原因：**
- 直接路径加载为了性能，绕过了Oracle的DML监控机制
- 如果实时收集统计信息，会拖慢导入速度50%
- Oracle选择性能优先，把统计信息收集留给用户

---

## 🔥 统计信息缺失/过期的严重后果

### 真实案例：统计信息过期导致查询慢10倍

#### **场景设置：**
```sql
-- 表实际情况：
-- - 总行数：1,000,000
-- - compare_status = 1 的数据：500,000行（50%）
-- - compare_status = 2 的数据：500,000行（50%）

-- 操作：昨天TRUNCATE + SQL*Loader导入，但忘记收集统计信息
```

#### **统计信息状态：**
```sql
SELECT table_name, num_rows, last_analyzed
FROM user_tables
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- 结果：
TABLE_NAME              NUM_ROWS  LAST_ANALYZED
----------------------- --------  -------------------
ZZ_POS_ITEM_DIFF_TEMP   0         2026-04-06 02:00:00  ← TRUNCATE后未更新
```

#### **查询1：没有统计信息**
```sql
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';
```

**执行计划（错误）：**
```
Plan hash value: 987654321

----------------------------------------------------------------------------------
| Id  | Operation                   | Name                    | Rows  | Cost   |
----------------------------------------------------------------------------------
|   0 | SELECT STATEMENT            |                         |     1 |     5  |
|   1 |  TABLE ACCESS BY INDEX ROWID| ZZ_POS_ITEM_DIFF_TEMP   |     1 |     5  |
|*  2 |   INDEX RANGE SCAN          | IDX_POS_DIFF_STATUS     |     1 |     2  |
----------------------------------------------------------------------------------

Predicate Information:
   2 - access("COMPARE_STATUS"='1')

优化器的错误推理：
- 统计信息显示num_rows=0，假设表很小
- 认为compare_status='1'只有1行数据
- 选择索引扫描（预计1次回表）

实际执行：
- 实际需要扫描50万行索引条目
- 实际需要50万次回表（随机IO）
- 实际耗时：15秒 ❌❌❌
```

#### **查询2：有正确统计信息**
```sql
-- 先收集统计信息
EXEC DBMS_STATS.GATHER_TABLE_STATS('FES', 'ZZ_POS_ITEM_DIFF_TEMP', cascade => TRUE);

-- 再执行相同查询
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';
```

**执行计划（正确）：**
```
Plan hash value: 123456789

----------------------------------------------------------------------------------
| Id  | Operation         | Name                    | Rows  | Cost (%CPU)| Time   |
----------------------------------------------------------------------------------
|   0 | SELECT STATEMENT  |                         |   500K|   2000 (1) | 00:00:01|
|*  1 |  TABLE ACCESS FULL| ZZ_POS_ITEM_DIFF_TEMP   |   500K|   2000 (1) | 00:00:01|
----------------------------------------------------------------------------------

Predicate Information:
   1 - filter("COMPARE_STATUS"='1')

优化器的正确推理：
- 统计信息显示num_rows=1,000,000
- 统计信息显示compare_status有2个唯一值，选择性50%
- 需要返回50万行数据（占总量50%）
- 全表扫描比索引扫描+50万次回表更快
- 选择全表扫描 ✅

实际执行：
- 顺序读取所有数据块
- 过滤出compare_status='1'的行
- 实际耗时：1.5秒 ✅✅✅（快10倍！）
```

**性能对比总结：**
```
┌─────────────────────────────────────────────────────┐
│  统计信息状态  │  优化器选择  │  实际耗时  │  差异   │
├─────────────────────────────────────────────────────┤
│  缺失/过期     │  索引扫描    │  15秒      │  基准   │
│  准确          │  全表扫描    │  1.5秒     │  10倍   │
└─────────────────────────────────────────────────────┘
```

---

## 💡 最佳实践：TRUNCATE + SQL*Loader后必须收集

### ❌ 错误做法（导致性能问题）

```bash
# 1. TRUNCATE表
sqlplus FES/password@db << EOF
TRUNCATE TABLE zz_pos_item_diff_temp;
EXIT;
EOF

# 2. SQL*Loader导入数据
sqlldr userid=FES/password control=load.ctl direct=true

# 3. 直接运行查询（问题！）
sqlplus FES/password@db << EOF
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';
EXIT;
EOF

# ❌ 问题：优化器使用旧的统计信息（num_rows=0），选择错误执行计划
```

### ✅ 正确做法（完整流程）

```bash
#!/bin/bash
# 完整的数据导入流程

echo "阶段1: 清空表数据..."
sqlplus -S FES/password@db << EOF
TRUNCATE TABLE zz_pos_item_diff_temp;
EXIT;
EOF

echo "阶段2: 导入数据..."
sqlldr userid=FES/password control=load.ctl direct=true

echo "阶段3: 收集统计信息（关键步骤！）..."
sqlplus -S FES/password@db << EOF
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    cascade => TRUE,  -- 同时收集索引统计信息
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
    degree => 4  -- 并行度
);

-- 验证统计信息
SELECT table_name, num_rows, last_analyzed 
FROM user_tables 
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
EXIT;
EOF

echo "阶段4: 运行查询..."
sqlplus -S FES/password@db << EOF
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';
EXIT;
EOF
```

### 📦 使用封装好的脚本（推荐）

已为你创建完整的一体化脚本：

```bash
# Linux/Unix
bash import_with_statistics.sh

# Windows
import_with_statistics.bat
```

**脚本包含：**
1. ✅ TRUNCATE清空数据
2. ✅ SQL*Loader导入数据
3. ✅ 自动收集统计信息
4. ✅ 验证统计信息准确性
5. ✅ 验证执行计划
6. ✅ 数据质量检查

---

## 🔍 如何检测统计信息问题？

### 检测脚本1：统计信息健康检查

```sql
-- ============================================================================
-- 统计信息健康检查
-- ============================================================================

PROMPT ======== 表统计信息健康检查 ========
SELECT 
    table_name,
    TO_CHAR(num_rows, '999,999,999') as num_rows,
    TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed,
    ROUND((SYSDATE - last_analyzed) * 24, 1) as hours_since_analyzed,
    stale_stats,
    CASE 
        WHEN last_analyzed IS NULL THEN '❌ 缺失统计信息（严重）'
        WHEN stale_stats = 'YES' THEN '❌ 统计信息已过期'
        WHEN last_analyzed < SYSDATE - 7 THEN '⚠️ 统计信息较旧（>7天）'
        WHEN last_analyzed < SYSDATE - 1 THEN '⚠️ 统计信息较旧（>1天）'
        ELSE '✅ 统计信息正常'
    END as health_status,
    CASE 
        WHEN last_analyzed IS NULL OR stale_stats = 'YES' THEN 
            '立即执行: EXEC DBMS_STATS.GATHER_TABLE_STATS(''FES'', ''' || table_name || ''', cascade=>TRUE);'
        WHEN last_analyzed < SYSDATE - 7 THEN '建议收集统计信息'
        ELSE '无需操作'
    END as recommendation
FROM user_tab_statistics
WHERE table_name IN ('ZZ_POS_ITEM_DIFF_TEMP', 'ZZ_PSUB_REF_DIFF_TEMP')
ORDER BY last_analyzed NULLS FIRST;
```

### 检测脚本2：实际数据 vs 统计信息对比

```sql
-- ============================================================================
-- 实际数据量 vs 统计信息对比
-- ============================================================================

WITH actual_counts AS (
    SELECT 
        'ZZ_POS_ITEM_DIFF_TEMP' as table_name,
        COUNT(*) as actual_rows
    FROM zz_pos_item_diff_temp
),
stats_counts AS (
    SELECT 
        table_name,
        num_rows as stats_rows,
        last_analyzed
    FROM user_tables
    WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
)
SELECT 
    a.table_name,
    TO_CHAR(a.actual_rows, '999,999,999') as actual_rows,
    TO_CHAR(NVL(s.stats_rows, 0), '999,999,999') as stats_rows,
    TO_CHAR(ABS(a.actual_rows - NVL(s.stats_rows, 0)), '999,999,999') as difference,
    CASE 
        WHEN s.stats_rows IS NULL THEN '❌ 缺失统计信息'
        WHEN s.stats_rows = 0 AND a.actual_rows > 0 THEN '❌ 统计信息为0，实际有数据'
        WHEN ABS(a.actual_rows - s.stats_rows) > a.actual_rows * 0.1 THEN 
            '⚠️ 差异' || ROUND(ABS(a.actual_rows - s.stats_rows) / a.actual_rows * 100, 1) || '%（>10%）'
        ELSE '✅ 一致（差异<10%）'
    END as status,
    TO_CHAR(s.last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed,
    CASE 
        WHEN s.stats_rows IS NULL OR ABS(a.actual_rows - s.stats_rows) > a.actual_rows * 0.1 THEN
            'EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname=>''FES'', tabname=>''' || a.table_name || ''', cascade=>TRUE);'
        ELSE NULL
    END as fix_command
FROM actual_counts a
LEFT JOIN stats_counts s ON a.table_name = s.table_name;
```

**典型输出（有问题）：**
```
TABLE_NAME              ACTUAL_ROWS  STATS_ROWS  DIFFERENCE  STATUS
----------------------- -----------  ----------  ----------  -------------------------
ZZ_POS_ITEM_DIFF_TEMP   1,000,000    0           1,000,000   ❌ 统计信息为0，实际有数据

FIX_COMMAND:
EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname=>'FES', tabname=>'ZZ_POS_ITEM_DIFF_TEMP', cascade=>TRUE);
```

---

## 📋 常见问题FAQ

### Q1: 我的表每天都会TRUNCATE + SQL*Loader导入，需要每天收集统计信息吗？

**A:** ✅ **是的，必须！这是最重要的一点！**

```bash
# 推荐做法：在导入脚本最后添加统计信息收集
sqlldr userid=FES/password control=load.ctl direct=true

# 立即收集统计信息
sqlplus -S FES/password << EOF
EXEC DBMS_STATS.GATHER_TABLE_STATS('FES', 'ZZ_POS_ITEM_DIFF_TEMP', cascade => TRUE);
EXIT;
EOF
```

**原因：**
- TRUNCATE和SQL*Loader都不触发自动统计信息收集
- 每天的数据量可能变化（100万 vs 120万），优化器需要最新信息
- 收集统计信息只需10-30秒（百万数据），但能避免10倍性能差异

---

### Q2: 统计信息收集很慢，能不能跳过？

**A:** ❌ **强烈不建议！** 但可以优化收集速度：

```sql
-- 优化方式1：降低采样率（快3倍，但稍不准确）
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    estimate_percent => 10,  -- 只采样10%（默认AUTO约30%）
    cascade => TRUE
);

-- 优化方式2：增加并行度（快5-8倍，需要多核CPU）
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
    degree => 8,  -- 并行度8（根据CPU核心数调整）
    cascade => TRUE
);

-- 优化方式3：仅收集表统计信息（快2倍，但索引统计信息不更新）
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    cascade => FALSE,  -- 不收集索引统计信息
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE
);
```

**性能对比：**
```
┌────────────────────────────────────────────────────────┐
│  收集方式              │  耗时（百万数据） │  准确性   │
├────────────────────────────────────────────────────────┤
│  完整收集（推荐）      │  30秒            │  100%     │
│  采样10%               │  10秒            │  95%      │
│  并行度8               │  5秒             │  100%     │
│  仅表统计信息          │  15秒            │  90%      │
│  不收集（错误）        │  0秒             │  0%❌     │
└────────────────────────────────────────────────────────┘
```

---

### Q3: 常规INSERT操作会自动收集统计信息吗？

**A:** ⚠️ **不是实时的，有延迟**

```
INSERT操作流程：
1. 执行INSERT → 更新DML监控计数器
2. 累计变化量达到10% → 标记为"STALE"
3. 等待维护窗口（默认晚上10点-凌晨6点）→ 自动收集

所以：
- 白天的大量INSERT不会立即触发统计信息收集
- 如果白天导入了大量数据（>10%），建议手动收集
```

**检测是否需要收集：**
```sql
SELECT 
    table_name,
    num_rows,
    stale_stats,  -- YES表示统计信息过期
    last_analyzed
FROM user_tab_statistics
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- 如果stale_stats='YES'，立即收集
EXEC DBMS_STATS.GATHER_TABLE_STATS('FES', 'ZZ_POS_ITEM_DIFF_TEMP', cascade => TRUE);
```

---

### Q4: 为什么收集了统计信息，执行计划还是不对？

**可能原因与解决方案：**

#### **1. 游标缓存未失效**
```sql
-- 问题：旧的执行计划还在共享池中
-- 方案1：收集统计信息时立即失效游标
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    cascade => TRUE,
    no_invalidate => FALSE  -- 立即失效相关游标 ✅
);

-- 方案2：清空共享池（慎用，影响所有会话）
ALTER SYSTEM FLUSH SHARED_POOL;
```

#### **2. 直方图缺失（数据分布不均）**
```sql
-- 检查直方图
SELECT column_name, histogram, num_buckets, num_distinct
FROM user_tab_col_statistics
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP'
  AND column_name = 'COMPARE_STATUS';

-- 如果histogram='NONE'但数据分布不均，重新收集
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    method_opt => 'FOR ALL COLUMNS SIZE AUTO',  -- 自动创建直方图
    cascade => TRUE
);
```

#### **3. 绑定变量窥探问题**
```sql
-- 问题：使用绑定变量时，优化器只看第一次执行的值
-- 查询1（第一次执行）：
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = :bind_var;
-- 如果:bind_var='1'（占50%），优化器选择全表扫描

-- 查询2（第二次执行，使用相同执行计划）：
-- 如果:bind_var='99'（占0.1%），仍用全表扫描（不合适）

-- 解决：使用字面值或SQL Profile
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '99';  -- 字面值
```

---

## ✅ 总结：统计信息快速参考

### 核心要点

| 概念 | 说明 | 关键点 |
|------|------|--------|
| **统计信息** | 描述数据特征的元数据（行数、唯一值、分布等） | 保存在数据字典，只有几KB |
| **作用** | 帮助优化器选择最优执行计划 | 影响查询性能**10倍以上** |
| **自动收集** | 数据变化>10%时，维护窗口自动收集 | 默认晚上10点-凌晨6点 |
| **不触发自动收集** | TRUNCATE, SQL*Loader, DROP+CREATE INDEX | **必须手动收集** ✅ |
| **检测方法** | 对比actual_rows vs stats_rows | 差异>10%需要收集 |
| **收集命令** | `DBMS_STATS.GATHER_TABLE_STATS` | `cascade=>TRUE`同时收集索引 |

---

### 必做检查清单（TRUNCATE + SQL*Loader后）

- [ ] 1. 数据导入完成
- [ ] 2. **执行`gather_statistics.sql`收集统计信息**（最关键！）
- [ ] 3. 验证`last_analyzed`为当前时间
- [ ] 4. 验证`num_rows`与实际数据量一致（差异<10%）
- [ ] 5. 验证`stale_stats='NO'`
- [ ] 6. 测试查询执行计划（应使用正确的访问路径）
- [ ] 7. 检查查询响应时间（应<1秒，百万数据）

---

### 快速命令参考

```sql
-- ✅ 收集单表统计信息（推荐，日常使用）
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    cascade => TRUE,  -- 同时收集索引统计信息
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,  -- 自动采样
    degree => 4,  -- 并行度
    no_invalidate => FALSE  -- 立即失效相关游标
);

-- ✅ 收集所有表统计信息（每月维护）
EXEC DBMS_STATS.GATHER_SCHEMA_STATS(
    ownname => 'FES', 
    cascade => TRUE,
    degree => 4
);

-- ✅ 验证统计信息
SELECT 
    table_name, 
    TO_CHAR(num_rows, '999,999,999') as num_rows,
    TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed,
    stale_stats
FROM user_tab_statistics
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- ✅ 查看执行计划
EXPLAIN PLAN FOR 
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format=>'ALL'));
```

---

## 📚 相关文档与脚本

- `gather_statistics.sql` - 日常统计信息收集脚本
- `import_with_statistics.bat` - Windows数据导入+统计信息收集一体化脚本
- `import_with_statistics.sh` - Linux数据导入+统计信息收集一体化脚本
- `索引优化完整指南.md` - 索引优化与统计信息指南

---

**创建时间：** 2026-04-07  
**最后更新：** 2026-04-07  
**维护人员：** FES Team

---

**重点总结：**

🔥 **TRUNCATE + SQL*Loader后必须手动收集统计信息！**  
🔥 **统计信息缺失/过期会导致查询慢10倍以上！**  
🔥 **每次数据导入后验证统计信息是否更新！**

