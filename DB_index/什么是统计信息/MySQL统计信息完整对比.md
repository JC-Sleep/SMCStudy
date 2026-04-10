# MySQL vs Oracle 统计信息完整对比

## 🎯 核心问题：MySQL也一样吗？

### 简短答案：
✅ **相似**：统计信息的作用和重要性一样（都影响优化器选择执行计划）  
⚠️ **不同**：自动收集机制、存储方式、手动收集命令都不同

---

## 📊 统计信息对比表

| 对比项 | Oracle | MySQL (InnoDB) |
|--------|--------|----------------|
| **统计信息作用** | ✅ 帮助优化器选择执行计划 | ✅ 帮助优化器选择执行计划 |
| **重要性** | ✅ 影响性能10倍以上 | ✅ 影响性能10倍以上 |
| **存储位置** | 数据字典表 | `mysql.innodb_table_stats`<br>`mysql.innodb_index_stats` |
| **统计信息持久化** | 默认持久化 | 可配置（`innodb_stats_persistent`） |
| **自动收集机制** | 维护窗口自动收集 | 数据变化>10%自动收集（默认） |
| **TRUNCATE后自动收集** | ❌ 不会 | ⚠️ 会重置统计信息为0 |
| **LOAD DATA后自动收集** | ❌ 不会（直接路径） | ⚠️ 取决于配置 |
| **手动收集命令** | `DBMS_STATS.GATHER_TABLE_STATS` | `ANALYZE TABLE` |
| **查看统计信息** | `user_tables`, `user_indexes` | `INFORMATION_SCHEMA.TABLES`<br>`mysql.innodb_table_stats` |

---

## 🔍 详细对比

### 1. 统计信息包含什么？

#### **Oracle:**
```sql
SELECT 
    table_name,
    num_rows,          -- 总行数
    blocks,            -- 数据块数
    avg_row_len,       -- 平均行长度
    last_analyzed      -- 最后分析时间
FROM user_tables
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
```

#### **MySQL (InnoDB):**
```sql
SELECT 
    table_name,
    table_rows,        -- 总行数（估算值）
    avg_row_length,    -- 平均行长度
    data_length,       -- 数据大小
    index_length,      -- 索引大小
    update_time        -- 最后更新时间
FROM information_schema.tables
WHERE table_schema = 'your_database'
  AND table_name = 'zz_pos_item_diff_temp';

-- 查看持久化的统计信息
SELECT * FROM mysql.innodb_table_stats 
WHERE table_name = 'zz_pos_item_diff_temp';

SELECT * FROM mysql.innodb_index_stats 
WHERE table_name = 'zz_pos_item_diff_temp';
```

**关键区别：**
- MySQL的`table_rows`是**估算值**（基于采样）
- Oracle的`num_rows`在收集后是**精确值**（除非使用采样）

---

### 2. 自动收集机制

#### **Oracle:**
```
触发条件：
- 数据变化 > 10%
- 在维护窗口（晚上10点-凌晨6点）自动收集

不触发的场景：
- TRUNCATE（DDL操作）
- SQL*Loader直接路径
- DROP + CREATE INDEX
```

#### **MySQL (InnoDB 5.6+):**
```sql
-- 查看当前配置
SHOW VARIABLES LIKE 'innodb_stats%';

-- 关键参数：
innodb_stats_auto_recalc = ON    -- 自动重新计算统计信息（默认ON）
innodb_stats_persistent = ON      -- 持久化统计信息（默认ON）
innodb_stats_on_metadata = OFF    -- 访问元数据时是否收集（默认OFF，性能考虑）
innodb_stats_sample_pages = 8     -- 采样页数（默认8页）
```

**自动收集规则：**
```
触发条件（当innodb_stats_auto_recalc=ON时）：
1. 表数据变化 > 10%（与Oracle类似）
2. 立即触发（不像Oracle等维护窗口）
3. 后台线程异步执行

特殊场景：
- TRUNCATE：会重置统计信息（与Oracle不同！）
- LOAD DATA：取决于innodb_stats_auto_recalc配置
- ALTER TABLE：会自动收集统计信息
```

---

### 3. TRUNCATE行为对比

#### **Oracle:**
```sql
TRUNCATE TABLE zz_pos_item_diff_temp;

-- 结果：
-- 1. 数据被清空 ✅
-- 2. 统计信息保持旧值 ❌（num_rows仍显示100万）
-- 3. 不触发自动统计信息收集 ❌
-- 4. 必须手动收集统计信息 ✅
```

#### **MySQL (InnoDB):**
```sql
TRUNCATE TABLE zz_pos_item_diff_temp;

-- 结果：
-- 1. 数据被清空 ✅
-- 2. 统计信息被重置为0 ✅（table_rows=0）
-- 3. 自动触发统计信息更新 ✅（如果innodb_stats_auto_recalc=ON）
-- 4. 通常不需要手动收集 ✅（但建议验证）
```

**关键区别：**
- Oracle: TRUNCATE后统计信息**保持旧值**，必须手动收集
- MySQL: TRUNCATE后统计信息**自动重置**，通常准确

---

### 4. LOAD DATA / SQL*Loader 对比

#### **Oracle (SQL*Loader):**
```bash
# 直接路径加载
sqlldr userid=FES/password control=load.ctl direct=true

# 结果：
# 1. 数据导入快10倍 ✅
# 2. 不触发DML监控 ❌
# 3. 不触发自动统计信息收集 ❌
# 4. 必须手动收集统计信息 ✅
```

#### **MySQL (LOAD DATA INFILE):**
```sql
LOAD DATA INFILE '/path/to/data.csv'
INTO TABLE zz_pos_item_diff_temp
FIELDS TERMINATED BY ','
LINES TERMINATED BY '\n';

-- 结果（当innodb_stats_auto_recalc=ON时）：
-- 1. 数据导入完成 ✅
-- 2. 如果变化>10%，触发自动统计信息收集 ✅
-- 3. 异步执行，可能有短暂延迟 ⚠️
-- 4. 建议手动验证统计信息准确性 ✅
```

**关键区别：**
- Oracle: SQL*Loader直接路径**永远不触发**自动收集
- MySQL: LOAD DATA **可能触发**自动收集（取决于配置）

---

### 5. 手动收集统计信息命令对比

#### **Oracle:**
```sql
-- 收集单表统计信息（包括索引）
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    cascade => TRUE,              -- 同时收集索引统计信息
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
    degree => 4,                  -- 并行度
    no_invalidate => FALSE        -- 立即失效游标
);

-- 收集索引统计信息
EXEC DBMS_STATS.GATHER_INDEX_STATS(
    ownname => 'FES',
    indname => 'IDX_POS_DIFF_STATUS'
);

-- 收集整个Schema
EXEC DBMS_STATS.GATHER_SCHEMA_STATS(
    ownname => 'FES',
    cascade => TRUE
);
```

#### **MySQL:**
```sql
-- 收集单表统计信息（包括索引）
ANALYZE TABLE zz_pos_item_diff_temp;

-- 收集多个表
ANALYZE TABLE table1, table2, table3;

-- 收集整个数据库（需要遍历所有表）
SELECT CONCAT('ANALYZE TABLE ', table_schema, '.', table_name, ';')
FROM information_schema.tables
WHERE table_schema = 'your_database'
  AND table_type = 'BASE TABLE';

-- 持久化统计信息（如果之前关闭了）
ALTER TABLE zz_pos_item_diff_temp STATS_PERSISTENT=1;
```

**命令对比：**
| 操作 | Oracle | MySQL |
|------|--------|-------|
| 收集单表 | `DBMS_STATS.GATHER_TABLE_STATS` | `ANALYZE TABLE` |
| 收集索引 | `DBMS_STATS.GATHER_INDEX_STATS` | `ANALYZE TABLE`（自动包含） |
| 收集Schema/Database | `DBMS_STATS.GATHER_SCHEMA_STATS` | 需要遍历所有表 |
| 并行收集 | 支持（degree参数） | 不支持（单表单线程） |

---

### 6. 验证统计信息

#### **Oracle:**
```sql
-- 检查表统计信息
SELECT 
    table_name, 
    num_rows,
    TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS') as last_analyzed,
    stale_stats
FROM user_tab_statistics
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';

-- 对比实际行数
SELECT COUNT(*) FROM zz_pos_item_diff_temp;
```

#### **MySQL:**
```sql
-- 检查表统计信息
SELECT 
    table_name,
    table_rows,
    avg_row_length,
    update_time
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'zz_pos_item_diff_temp';

-- 查看持久化统计信息
SELECT 
    database_name,
    table_name,
    n_rows,
    clustered_index_size,
    sum_of_other_index_sizes,
    last_update
FROM mysql.innodb_table_stats
WHERE table_name = 'zz_pos_item_diff_temp';

-- 对比实际行数
SELECT COUNT(*) FROM zz_pos_item_diff_temp;

-- 检查索引统计信息
SELECT 
    index_name,
    stat_name,
    stat_value,
    last_update
FROM mysql.innodb_index_stats
WHERE table_name = 'zz_pos_item_diff_temp'
ORDER BY index_name, stat_name;
```

---

## ⚠️ MySQL特有问题与解决方案

### 问题1：统计信息不准确（估算值）

**原因：**
```sql
-- MySQL的table_rows是估算值，基于采样
SHOW VARIABLES LIKE 'innodb_stats_sample_pages';
-- 默认值：8（只采样8个数据页）
```

**解决方案：**
```sql
-- 方式1：增加采样页数（提高准确性）
SET GLOBAL innodb_stats_sample_pages = 100;  -- 采样100页

-- 方式2：针对单表设置
ALTER TABLE zz_pos_item_diff_temp STATS_SAMPLE_PAGES=100;

-- 方式3：重新收集统计信息
ANALYZE TABLE zz_pos_item_diff_temp;
```

---

### 问题2：TRUNCATE + LOAD DATA后统计信息延迟

**场景：**
```sql
-- 1. TRUNCATE表
TRUNCATE TABLE zz_pos_item_diff_temp;

-- 2. LOAD DATA导入100万行
LOAD DATA INFILE '/data/pos_data.csv' 
INTO TABLE zz_pos_item_diff_temp;

-- 3. 立即查询（可能使用旧的执行计划）
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';
```

**问题：**
- 自动统计信息收集是**异步**的，可能有几秒延迟
- 优化器可能使用过期的统计信息

**解决方案：**
```sql
-- 导入后立即手动收集
LOAD DATA INFILE '/data/pos_data.csv' 
INTO TABLE zz_pos_item_diff_temp;

ANALYZE TABLE zz_pos_item_diff_temp;  -- 立即收集，确保准确

-- 验证统计信息
SELECT table_rows 
FROM information_schema.tables 
WHERE table_name = 'zz_pos_item_diff_temp';
```

---

### 问题3：禁用自动统计信息收集导致性能问题

**检查当前配置：**
```sql
SHOW VARIABLES LIKE 'innodb_stats_auto_recalc';

-- 如果显示OFF，自动收集已禁用
```

**如果禁用了（innodb_stats_auto_recalc=OFF）：**
```sql
-- 全局启用（重启后失效）
SET GLOBAL innodb_stats_auto_recalc = ON;

-- 永久启用（修改my.cnf或my.ini）
[mysqld]
innodb_stats_auto_recalc = ON

-- 针对单表启用
ALTER TABLE zz_pos_item_diff_temp STATS_AUTO_RECALC=1;
```

---

## ✅ MySQL最佳实践

### 实践1：TRUNCATE + LOAD DATA 完整流程

```sql
-- ============================================================================
-- MySQL数据导入最佳实践
-- ============================================================================

-- 步骤1：TRUNCATE表
TRUNCATE TABLE zz_pos_item_diff_temp;

-- 步骤2：LOAD DATA导入（可选：禁用索引加速导入）
ALTER TABLE zz_pos_item_diff_temp DISABLE KEYS;  -- 禁用非唯一索引

LOAD DATA INFILE '/data/pos_data.csv' 
INTO TABLE zz_pos_item_diff_temp
FIELDS TERMINATED BY ','
LINES TERMINATED BY '\n'
IGNORE 1 LINES;  -- 跳过标题行

ALTER TABLE zz_pos_item_diff_temp ENABLE KEYS;   -- 启用索引

-- 步骤3：立即收集统计信息（推荐！）
ANALYZE TABLE zz_pos_item_diff_temp;

-- 步骤4：验证统计信息
SELECT 
    table_name,
    table_rows,
    update_time
FROM information_schema.tables
WHERE table_name = 'zz_pos_item_diff_temp';

-- 步骤5：验证实际行数
SELECT COUNT(*) as actual_rows FROM zz_pos_item_diff_temp;

-- 步骤6：测试执行计划
EXPLAIN SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1'\G
```

---

### 实践2：配置持久化统计信息

```sql
-- 查看当前配置
SHOW VARIABLES LIKE 'innodb_stats%';

-- 推荐配置（my.cnf或my.ini）
[mysqld]
innodb_stats_persistent = ON              # 持久化统计信息
innodb_stats_auto_recalc = ON             # 自动重新计算
innodb_stats_persistent_sample_pages = 20 # 增加采样页数（默认20）
innodb_stats_on_metadata = OFF            # 避免元数据访问时收集（性能优化）

-- 针对单表配置
ALTER TABLE zz_pos_item_diff_temp 
    STATS_PERSISTENT=1,
    STATS_AUTO_RECALC=1,
    STATS_SAMPLE_PAGES=50;  -- 更高的采样率
```

---

### 实践3：定期维护统计信息

```sql
-- ============================================================================
-- MySQL统计信息维护脚本（定期执行）
-- ============================================================================

-- 查找需要收集统计信息的表（table_rows为NULL或很久未更新）
SELECT 
    table_schema,
    table_name,
    table_rows,
    update_time,
    TIMESTAMPDIFF(DAY, update_time, NOW()) as days_since_update
FROM information_schema.tables
WHERE table_schema = 'your_database'
  AND table_type = 'BASE TABLE'
  AND (table_rows IS NULL 
       OR update_time < DATE_SUB(NOW(), INTERVAL 7 DAY))
ORDER BY days_since_update DESC;

-- 批量收集统计信息
ANALYZE TABLE 
    table1, 
    table2, 
    table3,
    zz_pos_item_diff_temp;

-- 验证收集结果
SELECT 
    table_name,
    table_rows,
    update_time
FROM information_schema.tables
WHERE table_schema = 'your_database'
  AND table_name IN ('table1', 'table2', 'table3', 'zz_pos_item_diff_temp');
```

---

## 📋 快速对比总结

### Oracle vs MySQL：何时必须手动收集统计信息？

| 场景 | Oracle | MySQL (InnoDB) |
|------|--------|----------------|
| **TRUNCATE** | ✅ **必须**手动收集 | ⚠️ 建议手动收集（虽然会自动重置） |
| **SQL*Loader / LOAD DATA** | ✅ **必须**手动收集 | ⚠️ 建议手动收集（确保及时更新） |
| **TRUNCATE + 批量导入** | ✅ **必须**手动收集 | ✅ **建议**手动收集 |
| **DROP + CREATE INDEX** | ✅ **必须**手动收集 | ⚠️ 通常自动收集，但建议验证 |
| **大量INSERT (>10%)** | ⚠️ 等维护窗口自动收集 | ⚠️ 自动收集，但可能有延迟 |

**结论：**
- **Oracle**：几乎所有批量操作后都**必须**手动收集
- **MySQL**：批量操作后**建议**手动收集（虽然有自动收集，但不一定及时）

---

## 🔧 MySQL统计信息快速参考

### 最常用命令

```sql
-- ✅ 收集单表统计信息
ANALYZE TABLE zz_pos_item_diff_temp;

-- ✅ 收集多表统计信息
ANALYZE TABLE table1, table2, table3;

-- ✅ 查看统计信息
SELECT table_name, table_rows, update_time
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'zz_pos_item_diff_temp';

-- ✅ 查看持久化统计信息
SELECT * FROM mysql.innodb_table_stats 
WHERE table_name = 'zz_pos_item_diff_temp';

-- ✅ 检查配置
SHOW VARIABLES LIKE 'innodb_stats%';

-- ✅ 启用自动统计信息收集
SET GLOBAL innodb_stats_auto_recalc = ON;
```

### 检查清单（TRUNCATE + LOAD DATA后）

- [ ] 数据导入完成
- [ ] 执行`ANALYZE TABLE`收集统计信息
- [ ] 验证`table_rows`与实际数据量一致
- [ ] 验证`update_time`为当前时间
- [ ] 测试查询执行计划
- [ ] 检查查询性能

---

## 📚 总结

### 主要相似点：
1. ✅ 统计信息的作用相同（帮助优化器）
2. ✅ 重要性相同（影响性能10倍以上）
3. ✅ 都需要定期维护

### 主要区别：
1. ⚠️ **自动收集机制**：MySQL更积极（立即触发），Oracle更保守（等维护窗口）
2. ⚠️ **TRUNCATE行为**：MySQL自动重置统计信息，Oracle保持旧值
3. ⚠️ **手动收集命令**：MySQL用`ANALYZE TABLE`，Oracle用`DBMS_STATS`
4. ⚠️ **统计信息准确性**：MySQL是估算值（基于采样），Oracle可以是精确值
5. ⚠️ **配置灵活性**：MySQL可针对单表配置，Oracle全局配置

### 最佳实践：
🔥 **无论Oracle还是MySQL，批量数据操作（TRUNCATE + 导入）后，都建议立即手动收集统计信息！**

---

**相关文档：**
- `Oracle统计信息完整解析.md` - Oracle统计信息详解
- `统计信息快速参考.md` - Oracle快速参考
- 本文档：`MySQL统计信息完整对比.md` - MySQL与Oracle对比

---

**创建时间：** 2026-04-07  
**最后更新：** 2026-04-07

