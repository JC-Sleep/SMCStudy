# Oracle统计信息深度解析 - 从原理到实践

## 📚 第一部分：统计信息是什么？

### 核心概念
**统计信息（Statistics）≠ 索引本身**

```
┌─────────────────────────────────────────────────────────────┐
│                      Oracle数据层级                          │
├─────────────────────────────────────────────────────────────┤
│  1. 实际数据（表、索引）    ← 真实存储在磁盘上              │
│  2. 统计信息（Metadata）    ← 描述数据特征的"说明书"         │
│  3. 优化器（CBO）           ← 根据统计信息选择执行计划       │
└─────────────────────────────────────────────────────────────┘
```

### 统计信息包含什么？

#### **表统计信息：**
```sql
SELECT
    table_name,
    num_rows,          -- 总行数（例如：1,000,000）
    blocks,            -- 占用的数据块数（例如：15,000）
    avg_row_len,       -- 平均行长度（字节，例如：120）
    last_analyzed      -- 最后分析时间
FROM user_tables
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
```

#### **索引统计信息：**
```sql
SELECT
    index_name,
    num_rows,          -- 索引条目数
    distinct_keys,     -- 唯一键数量（选择性指标）
    clustering_factor, -- 聚集因子（数据物理排序程度）
    blevel,            -- B树深度（访问开销）
    leaf_blocks        -- 叶子块数量
FROM user_indexes
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
```

#### **列统计信息：**
```sql
SELECT
    column_name,
    num_distinct,      -- 唯一值数量（选择性）
    density,           -- 密度（1/num_distinct）
    num_nulls,         -- NULL值数量
    histogram          -- 直方图类型（数据分布）
FROM user_tab_col_statistics
WHERE table_name = 'ZZ_POS_ITEM_DIFF_TEMP';
```

---

## 🎯 第二部分：统计信息的作用

### 优化器如何使用统计信息？

#### **场景1：没有统计信息**
```sql
-- 假设表有100万行，但统计信息为NULL
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = 1;
```

**优化器的困境：**
```
优化器：我不知道这个表有多少行...
优化器：我不知道compare_status有几个不同的值...
优化器：我不知道compare_status=1的数据有多少...
优化器：随便选一个执行计划吧... 😵（可能选错！）

结果：可能选择全表扫描，耗时5秒
```

#### **场景2：有完整统计信息**
```sql
-- 统计信息显示：
-- num_rows = 1,000,000
-- compare_status有2个唯一值（1和2）
-- compare_status=1的数据约50万行
-- idx_pos_diff_status索引的clustering_factor = 100,000
```

**优化器的决策过程：**
```
优化器：表有100万行
优化器：compare_status有2个值，选择性50%
优化器：如果用索引，需要访问50万行 → 回表成本高
优化器：全表扫描可能更快（一次读取所有数据块）
优化器：选择全表扫描！✅（正确决策）

结果：全表扫描，耗时1.5秒（比盲目用索引快）
```

#### **场景3：查询条件更精准**
```sql
SELECT * FROM zz_pos_item_diff_temp
WHERE TRUNC(create_date) = TRUNC(SYSDATE)
  AND compare_status = 1
  AND subscriber_nb = '123321211';
```

**优化器的决策（有统计信息）：**
```
优化器：TRUNC(create_date)=今天 → 过滤到1000行（千分之一）
优化器：compare_status=1 → 再过滤50%，约500行
优化器：subscriber_nb特定值 → 再过滤99.9%，约5行
优化器：使用idx_pos_diff_new_data索引！✅
优化器：预计成本：5次索引访问+5次回表=10个IO

结果：索引扫描，耗时0.05秒 🚀
```

---

## ⚙️ 第三部分：统计信息的自动收集机制

### Oracle自动统计信息收集规则

```
┌──────────────────────────────────────────────────────────────┐
│         Oracle统计信息自动收集机制（11g+版本）               │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  触发条件：表数据变化 > 10%                                  │
│  检测方式：通过 DML 操作监控（INSERT/UPDATE/DELETE）        │
│  执行时间：默认在维护窗口（晚上10点-凌晨6点）               │
│                                                              │
│  ✅ 自动收集的场景：                                         │
│    - 常规 INSERT/UPDATE/DELETE（累计变化>10%）             │
│    - CTAS (CREATE TABLE AS SELECT)                          │
│    - 维护窗口内自动任务                                      │
│                                                              │
│  ❌ 不会自动收集的场景：                                     │
│    - TRUNCATE（直接清空，不记录DML）                        │
│    - SQL*Loader（直接路径加载，绕过DML监控）                │
│    - TRUNCATE + SQL*Loader（两者都不触发）                  │
│    - DROP + CREATE INDEX（新建索引无统计信息）              │
│    - IMPDP（数据泵导入，除非使用特定参数）                  │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 🔍 第四部分：为什么SQL*Loader和TRUNCATE不自动统计？

### 原因1：性能优先设计

#### **SQL*Loader直接路径加载（DIRECT=TRUE）**
```bash
# 直接路径加载示例
sqlldr userid=FES/password control=load.ctl direct=true
```

**工作机制：**
```
普通INSERT：
应用 → SQL引擎 → Buffer Cache → Redo Log → 数据文件 → 触发监控
                                                      ↓
                                              （自动统计信息更新）

SQL*Loader直接路径：
应用 → 直接写入数据文件（绕过SQL引擎和Buffer Cache）
                ↓
        （不触发监控，提升性能10倍）
                ↓
        ❌ 不自动收集统计信息
```

**原因：**
- 直接路径加载速度快10倍（每秒可导入百万行）
- 如果实时收集统计信息，会拖慢导入速度50%
- Oracle选择性能优先，把统计信息收集留给用户

#### **TRUNCATE操作**
```sql
TRUNCATE TABLE zz_pos_item_diff_temp;
```

**工作机制：**
```
DELETE（DML操作）：
逐行删除 → 记录Redo Log → 触发监控 → 更新统计信息
速度：100万行需要30秒

TRUNCATE（DDL操作）：
直接释放数据段 → 不记录Redo Log → 不触发监控
速度：100万行只需0.5秒
         ↓
  ❌ 不自动收集统计信息
```

**TRUNCATE后的统计信息状态：**
```sql
-- TRUNCATE前
num_rows = 1,000,000

-- TRUNCATE后（统计信息保留旧值！）
num_rows = 1,000,000  ← 仍然显示旧值！实际已清空

-- 导致问题：优化器以为表还有100万行，可能选择错误执行计划
```

---

## 💡 第五部分：最佳实践

### 实践1：SQL*Loader后必须收集统计信息

#### **错误做法：**
```bash
# 1. TRUNCATE表
sqlplus FES/password@db << EOF
TRUNCATE TABLE zz_pos_item_diff_temp;
EXIT;
EOF

# 2. SQL*Loader导入数据
sqlldr userid=FES/password control=load.ctl direct=true

# 3. 直接运行查询
sqlplus FES/password@db << EOF
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = 1;
EXIT;
EOF

# ❌ 问题：优化器使用旧的统计信息（num_rows=0或旧值），可能全表扫描
```

#### **正确做法：**
```bash
# 1. TRUNCATE表
sqlplus FES/password@db << EOF
TRUNCATE TABLE zz_pos_item_diff_temp;
EXIT;
EOF

# 2. SQL*Loader导入数据
sqlldr userid=FES/password control=load.ctl direct=true

# 3. 收集统计信息（关键步骤！）
sqlplus FES/password@db << EOF
EXEC DBMS_STATS.GATHER_TABLE_STATS(
    ownname => 'FES',
    tabname => 'ZZ_POS_ITEM_DIFF_TEMP',
    cascade => TRUE,
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
    degree => 4
);
EXIT;
EOF

# 4. 运行查询
sqlplus FES/password@db << EOF
SELECT * FROM zz_pos_item_diff_temp WHERE compare_status = 1;
EXIT;
EOF

# ✅ 优化器使用准确的统计信息，选择最优执行计划
```

---

### 实践2：一体化脚本（导入+统计信息收集）

<function_calls>
<invoke name="create_file">
<parameter name="content">#!/bin/bash
# ============================================================================
# 数据导入与统计信息收集一体化脚本
# 用途：TRUNCATE + SQL*Loader + 统计信息收集
# ============================================================================

# 配置参数
DB_USER="FES"
DB_PASSWORD="your_password"
DB_INSTANCE="database"
TABLE_NAME="ZZ_POS_ITEM_DIFF_TEMP"
CONTROL_FILE="load_pos_item_diff.ctl"
DATA_FILE="pos_item_diff.dat"

echo "======== 开始数据导入流程 ========"
echo "开始时间: $(date '+%Y-%m-%d %H:%M:%S')"

# ============================================================================
# 阶段1：清空表数据
# ============================================================================
echo ""
echo "阶段1: 清空表数据..."
sqlplus -S ${DB_USER}/${DB_PASSWORD}@${DB_INSTANCE} << EOF
SET SERVEROUTPUT ON;
WHENEVER SQLERROR EXIT SQL.SQLCODE;

BEGIN
    EXECUTE IMMEDIATE 'TRUNCATE TABLE ${TABLE_NAME}';
    DBMS_OUTPUT.PUT_LINE('✓ 表已清空: ${TABLE_NAME}');
EXCEPTION
    WHEN OTHERS THEN
        DBMS_OUTPUT.PUT_LINE('❌ 清空失败: ' || SQLERRM);
        RAISE;
END;
/
EXIT;
EOF

if [ $? -ne 0 ]; then
    echo "❌ 表清空失败，终止流程"
    exit 1
fi

# ============================================================================
# 阶段2：SQL*Loader导入数据
# ============================================================================
echo ""
echo "阶段2: SQL*Loader导入数据..."

sqlldr userid=${DB_USER}/${DB_PASSWORD}@${DB_INSTANCE} \
       control=${CONTROL_FILE} \
       data=${DATA_FILE} \
       direct=true \
       errors=1000 \
       log=sqlldr_${TABLE_NAME}_$(date +%Y%m%d_%H%M%S).log

if [ $? -ne 0 ]; then
    echo "❌ 数据导入失败，终止流程"
    exit 1
fi

# 检查导入记录数
LOADED_ROWS=$(grep "successfully loaded" sqlldr_${TABLE_NAME}_*.log | tail -1 | awk '{print $1}')
echo "✓ 成功导入 ${LOADED_ROWS} 行数据"

# ============================================================================
# 阶段3：收集统计信息（关键步骤！）
# ============================================================================
echo ""
echo "阶段3: 收集统计信息..."

sqlplus -S ${DB_USER}/${DB_PASSWORD}@${DB_INSTANCE} << EOF
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
        ownname          => '${DB_USER}',
        tabname          => '${TABLE_NAME}',
        cascade          => TRUE,              -- 同时收集索引统计信息
        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
        method_opt       => 'FOR ALL COLUMNS SIZE AUTO',
        degree           => 4,                 -- 并行度
        granularity      => 'ALL',
        no_invalidate    => FALSE              -- 立即失效相关游标
    );

    v_end_time := SYSTIMESTAMP;

    -- 验证统计信息
    SELECT num_rows INTO v_num_rows
    FROM user_tables
    WHERE table_name = '${TABLE_NAME}';

    DBMS_OUTPUT.PUT_LINE('✓ 统计信息收集完成');
    DBMS_OUTPUT.PUT_LINE('  - 表行数: ' || v_num_rows);
    DBMS_OUTPUT.PUT_LINE('  - 耗时: ' || EXTRACT(SECOND FROM (v_end_time - v_start_time)) || ' 秒');

    -- 验证索引统计信息
    FOR idx IN (SELECT index_name, num_rows, last_analyzed
                FROM user_indexes
                WHERE table_name = '${TABLE_NAME}') LOOP
        DBMS_OUTPUT.PUT_LINE('  - 索引: ' || idx.index_name ||
                           ', 行数: ' || idx.num_rows ||
                           ', 分析时间: ' || TO_CHAR(idx.last_analyzed, 'YYYY-MM-DD HH24:MI:SS'));
    END LOOP;
END;
/

-- 验证数据质量
PROMPT
PROMPT ======== 数据质量验证 ========
SELECT
    '总行数' as metric,
    COUNT(*) as value
FROM ${TABLE_NAME}
UNION ALL
SELECT
    'compare_status=1' as metric,
    COUNT(*) as value
FROM ${TABLE_NAME}
WHERE compare_status = '1'
UNION ALL
SELECT
    'compare_status=2' as metric,
    COUNT(*) as value
FROM ${TABLE_NAME}
WHERE compare_status = '2'
UNION ALL
SELECT
    '今日数据' as metric,
    COUNT(*) as value
FROM ${TABLE_NAME}
WHERE TRUNC(create_date) = TRUNC(SYSDATE);

EXIT;
EOF

if [ $? -ne 0 ]; then
    echo "❌ 统计信息收集失败"
    exit 1
fi

# ============================================================================
# 阶段4：验证执行计划
# ============================================================================
echo ""
echo "阶段4: 验证执行计划..."

sqlplus -S ${DB_USER}/${DB_PASSWORD}@${DB_INSTANCE} << EOF
SET LINESIZE 200;
SET PAGESIZE 1000;

EXPLAIN PLAN FOR
SELECT * FROM ${TABLE_NAME}
WHERE TRUNC(create_date) = TRUNC(SYSDATE)
  AND compare_status = '1'
  AND subscriber_nb = 'TEST123'
ORDER BY compare_status, cellular_nb, subscriber_nb;

PROMPT ======== 执行计划验证 ========
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(format=>'BASIC +COST +PREDICATE'));

EXIT;
EOF

echo ""
echo "======== 数据导入流程完成 ========"
echo "完成时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""
echo "关键检查项："
echo "1. 统计信息已收集（last_analyzed为当前时间）"
echo "2. 执行计划使用索引（不是全表扫描）"
echo "3. 数据行数匹配预期"

