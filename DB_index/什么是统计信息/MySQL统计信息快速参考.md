# MySQL统计信息快速参考卡片

## 🎯 3秒理解（MySQL版本）

```
统计信息 = 数据的"说明书"（不是索引本身）
作用 = 帮助优化器选择最快的查询路径
重要性 = 缺失/过期会导致查询慢10倍以上！

与Oracle的区别：MySQL更积极自动收集，但仍建议手动验证
```

---

## ⚡ 最常用命令（MySQL）

### 收集统计信息（最重要！）
```sql
-- 收集单表统计信息（推荐）
ANALYZE TABLE zz_pos_item_diff_temp;

-- 收集多表统计信息
ANALYZE TABLE table1, table2, table3;

-- 本地临时收集（不持久化）
ANALYZE NO_WRITE_TO_BINLOG TABLE zz_pos_item_diff_temp;
```

### 验证统计信息
```sql
-- 快速查看
SELECT 
    table_name, 
    table_rows,
    avg_row_length,
    update_time
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'zz_pos_item_diff_temp';

-- 查看持久化统计信息（更详细）
SELECT 
    table_name,
    n_rows,
    clustered_index_size,
    sum_of_other_index_sizes,
    last_update
FROM mysql.innodb_table_stats
WHERE table_name = 'zz_pos_item_diff_temp';

-- 对比实际行数
SELECT 
    (SELECT COUNT(*) FROM zz_pos_item_diff_temp) as actual_rows,
    (SELECT table_rows FROM information_schema.tables 
     WHERE table_name='zz_pos_item_diff_temp' AND table_schema=DATABASE()) as stats_rows;
```

---

## 🔥 关键场景（MySQL特定）

### ⚠️ 需要手动收集统计信息的场景

| 场景 | 自动收集？ | 建议操作 | 原因 |
|------|-----------|---------|------|
| **TRUNCATE** | ✅ 会重置 | 建议手动验证 | 自动重置为0，但建议确认 |
| **LOAD DATA INFILE** | ⚠️ 可能异步收集 | **立即手动收集** | 异步收集有延迟 |
| **TRUNCATE + LOAD DATA** | ⚠️ 可能延迟 | **必须手动收集** | 确保统计信息及时更新 |
| **大量INSERT (>10%)** | ⚠️ 异步收集 | 建议手动收集 | 异步收集可能有延迟 |
| **ALTER TABLE ADD INDEX** | ✅ 自动收集 | 通常无需操作 | MySQL自动处理 |
| **禁用了auto_recalc** | ❌ 不会 | **必须手动收集** | 需要先启用auto_recalc |

---

## ✅ 每日工作流程（TRUNCATE + LOAD DATA）

### 方式1：基础流程
```sql
-- 步骤1: 清空表
TRUNCATE TABLE zz_pos_item_diff_temp;

-- 步骤2: 导入数据
LOAD DATA INFILE '/data/pos_data.csv'
INTO TABLE zz_pos_item_diff_temp
FIELDS TERMINATED BY ','
LINES TERMINATED BY '\n'
IGNORE 1 LINES;

-- 步骤3: 收集统计信息（关键！）
ANALYZE TABLE zz_pos_item_diff_temp;

-- 步骤4: 验证统计信息
SELECT table_rows, update_time 
FROM information_schema.tables 
WHERE table_name='zz_pos_item_diff_temp' AND table_schema=DATABASE();
```

### 方式2：优化流程（禁用索引加速导入）
```sql
-- 步骤1: 清空表
TRUNCATE TABLE zz_pos_item_diff_temp;

-- 步骤2: 禁用非唯一索引（加速导入）
ALTER TABLE zz_pos_item_diff_temp DISABLE KEYS;

-- 步骤3: 导入数据
LOAD DATA INFILE '/data/pos_data.csv'
INTO TABLE zz_pos_item_diff_temp
FIELDS TERMINATED BY ','
LINES TERMINATED BY '\n'
IGNORE 1 LINES;

-- 步骤4: 启用索引
ALTER TABLE zz_pos_item_diff_temp ENABLE KEYS;

-- 步骤5: 收集统计信息（关键！）
ANALYZE TABLE zz_pos_item_diff_temp;

-- 步骤6: 验证
SELECT 
    COUNT(*) as actual_rows,
    (SELECT table_rows FROM information_schema.tables 
     WHERE table_name='zz_pos_item_diff_temp' AND table_schema=DATABASE()) as stats_rows;
```

---

## 🚨 故障排查

### 问题1：查询很慢
```sql
-- 检查统计信息
SELECT table_name, table_rows, update_time
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'zz_pos_item_diff_temp';

-- 如果update_time很久以前或table_rows不准确 → 立即收集
ANALYZE TABLE zz_pos_item_diff_temp;

-- 查看执行计划
EXPLAIN SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1'\G
```

### 问题2：统计信息不准确（table_rows与实际差距大）
```sql
-- 问题：MySQL的table_rows是估算值，可能不准确
-- 原因：采样页数太少

-- 检查当前采样页数
SHOW VARIABLES LIKE 'innodb_stats_sample_pages';

-- 解决方案1：增加全局采样页数
SET GLOBAL innodb_stats_sample_pages = 100;  -- 默认20

-- 解决方案2：针对单表增加采样页数
ALTER TABLE zz_pos_item_diff_temp STATS_SAMPLE_PAGES=100;

-- 解决方案3：重新收集统计信息
ANALYZE TABLE zz_pos_item_diff_temp;

-- 验证结果
SELECT 
    (SELECT COUNT(*) FROM zz_pos_item_diff_temp) as actual,
    (SELECT table_rows FROM information_schema.tables 
     WHERE table_name='zz_pos_item_diff_temp' AND table_schema=DATABASE()) as stats,
    ABS((SELECT COUNT(*) FROM zz_pos_item_diff_temp) - 
        (SELECT table_rows FROM information_schema.tables 
         WHERE table_name='zz_pos_item_diff_temp' AND table_schema=DATABASE())) as difference;
```

### 问题3：统计信息不自动更新
```sql
-- 检查自动收集配置
SHOW VARIABLES LIKE 'innodb_stats_auto_recalc';

-- 如果显示OFF，启用自动收集
SET GLOBAL innodb_stats_auto_recalc = ON;

-- 针对单表启用
ALTER TABLE zz_pos_item_diff_temp STATS_AUTO_RECALC=1;

-- 立即收集统计信息
ANALYZE TABLE zz_pos_item_diff_temp;
```

### 问题4：LOAD DATA后统计信息延迟
```sql
-- 问题：自动收集是异步的，可能有几秒延迟
-- 解决：导入后立即手动收集

LOAD DATA INFILE '/data/pos_data.csv'
INTO TABLE zz_pos_item_diff_temp
FIELDS TERMINATED BY ','
LINES TERMINATED BY '\n';

-- 立即收集（不等异步任务）
ANALYZE TABLE zz_pos_item_diff_temp;

-- 验证已更新
SELECT table_rows, update_time 
FROM information_schema.tables 
WHERE table_name='zz_pos_item_diff_temp' AND table_schema=DATABASE();
```

---

## 📊 MySQL vs Oracle对比速查

| 对比项 | Oracle | MySQL |
|--------|--------|-------|
| **收集命令** | `DBMS_STATS.GATHER_TABLE_STATS` | `ANALYZE TABLE` |
| **TRUNCATE后** | ❌ 保持旧值，必须手动收集 | ✅ 自动重置为0 |
| **批量导入后** | ❌ 必须手动收集 | ⚠️ 异步收集，建议手动 |
| **统计信息准确性** | 可精确（非采样） | 估算值（基于采样） |
| **自动收集时机** | 维护窗口（延迟大） | 立即异步（延迟小） |
| **查看统计信息** | `user_tables` | `information_schema.tables` |

**结论：** MySQL的自动收集更积极，但批量操作后仍**建议手动收集**以确保及时更新。

---

## 🔧 高级配置

### 推荐配置（my.cnf或my.ini）
```ini
[mysqld]
# 持久化统计信息
innodb_stats_persistent = ON

# 自动重新计算统计信息
innodb_stats_auto_recalc = ON

# 采样页数（提高准确性，默认20）
innodb_stats_persistent_sample_pages = 50

# 禁用元数据访问时收集（性能优化）
innodb_stats_on_metadata = OFF
```

### 针对单表优化
```sql
-- 为重要的表设置更高的采样率和启用自动收集
ALTER TABLE zz_pos_item_diff_temp 
    STATS_PERSISTENT=1,           -- 持久化统计信息
    STATS_AUTO_RECALC=1,          -- 自动收集
    STATS_SAMPLE_PAGES=100;       -- 更高的采样率

-- 验证配置
SELECT 
    name,
    stats_persistent,
    stats_auto_recalc,
    stats_sample_pages
FROM mysql.innodb_table_stats
WHERE name = 'your_database/zz_pos_item_diff_temp';
```

### 检查哪些表需要收集统计信息
```sql
-- 查找统计信息过期的表
SELECT 
    table_schema,
    table_name,
    table_rows,
    update_time,
    TIMESTAMPDIFF(DAY, update_time, NOW()) as days_since_update
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE'
  AND (update_time IS NULL 
       OR update_time < DATE_SUB(NOW(), INTERVAL 7 DAY))
ORDER BY days_since_update DESC;

-- 批量收集
-- （注意：需要逐个执行，MySQL不支持一次收集所有表）
ANALYZE TABLE table1;
ANALYZE TABLE table2;
ANALYZE TABLE zz_pos_item_diff_temp;
```

---

## 📋 检查清单

**每次TRUNCATE + LOAD DATA后：**

- [ ] 数据导入完成
- [ ] **执行`ANALYZE TABLE`收集统计信息**
- [ ] `update_time`为当前时间
- [ ] `table_rows`与实际一致（差异<10%）
- [ ] 测试查询执行计划（`EXPLAIN`）
- [ ] 验证查询性能正常

**每周维护：**

- [ ] 检查所有表的`update_time`
- [ ] 收集超过7天未更新的表统计信息
- [ ] 验证`innodb_stats_auto_recalc=ON`
- [ ] 检查执行计划

---

## 📞 常见问题速查

| 问题 | 答案 |
|------|------|
| **TRUNCATE会自动收集统计信息吗？** | ✅ 会自动重置为0，但建议验证 |
| **LOAD DATA会自动收集吗？** | ⚠️ 异步收集，可能延迟，建议手动 |
| **INSERT会自动收集吗？** | ⚠️ 变化>10%异步收集，可能延迟 |
| **收集统计信息需要多久？** | 百万数据约5-15秒（比Oracle快） |
| **table_rows准确吗？** | ⚠️ 估算值，基于采样（可调整采样率） |
| **如何提高准确性？** | 增加`innodb_stats_sample_pages` |
| **统计信息会占用多少空间？** | 很小，存在`mysql.innodb_*_stats`表 |
| **与Oracle的主要区别？** | MySQL更积极自动收集，但仍建议手动验证 |

---

## 💡 MySQL特有提示

### 1. 统计信息是估算值
```sql
-- MySQL的table_rows不是精确值！
SELECT table_rows FROM information_schema.tables 
WHERE table_name='zz_pos_item_diff_temp';  -- 可能显示：987,654

SELECT COUNT(*) FROM zz_pos_item_diff_temp;  -- 实际：1,000,000

-- 差异约1-2%是正常的（基于采样）
```

### 2. 自动收集是异步的
```sql
-- LOAD DATA后，统计信息可能延迟几秒才更新
LOAD DATA INFILE '/data/pos_data.csv' INTO TABLE zz_pos_item_diff_temp;

-- 此时查询可能使用旧统计信息
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = '1';

-- 解决：立即手动收集
ANALYZE TABLE zz_pos_item_diff_temp;
```

### 3. 可以禁用元数据访问时收集（性能优化）
```sql
-- 避免每次访问INFORMATION_SCHEMA时触发统计信息收集
SET GLOBAL innodb_stats_on_metadata = OFF;  -- 推荐设置
```

---

## 📚 完整文档

- **`MySQL统计信息完整对比.md`** - MySQL与Oracle详细对比（必读）
- `Oracle统计信息完整解析.md` - Oracle统计信息详解
- `统计信息快速参考.md` - Oracle快速参考
- 本文档：`MySQL统计信息快速参考.md` - MySQL快速参考

---

## 🎯 最重要的一句话（MySQL版本）

🔥 **虽然MySQL会自动收集统计信息，但TRUNCATE + LOAD DATA后，仍建议立即执行 `ANALYZE TABLE` 确保统计信息及时准确！** 🔥

---

**与Oracle的关键区别：**
- Oracle：几乎总是**必须**手动收集
- MySQL：**建议**手动收集（虽然有自动收集，但可能延迟）

---

**打印提示：** 建议打印本页贴在工位，作为MySQL日常参考

