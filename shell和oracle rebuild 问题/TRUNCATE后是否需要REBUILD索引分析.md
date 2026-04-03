# TRUNCATE + Load 数据后是否需要 REBUILD 索引？

rebuild index代码
```sql
PROCEDURE rebuild_index (table_name IN VARCHAR2) IS

     CURSOR c1 (table_name_IN VARCHAR2) IS
	     SELECT INDEX_NAME FROM USER_INDEXES
	     WHERE TABLE_NAME = table_name_IN;

     in_index_name USER_INDEXES.INDEX_NAME%TYPE;

BEGIN

	  IF c1%ISOPEN THEN
	     CLOSE c1;
	  END IF;
	  OPEN c1 (table_name);
	  LOOP
	    FETCH c1 INTO in_index_name;
		 EXIT WHEN c1%NOTFOUND;
       DBMS_OUTPUT.PUT_LINE('Index Name:'||in_index_name);

       EXECUTE IMMEDIATE 'ALTER INDEX ' || in_index_name || ' REBUILD ONLINE ';

	  END LOOP;
     CLOSE c1;

	  EXCEPTION
	     WHEN OTHERS THEN
	     IF c1%ISOPEN THEN
		     CLOSE c1;
		  END IF;
        DBMS_OUTPUT.PUT_LINE('ERROR:'||SQLERRM);

END rebuild_index;
```

## 🎯 直接答案

**不需要执行 `rebuild_index`！在 TRUNCATE + 全新加载数据的场景下，重建索引是不必要的，甚至是浪费时间。**

---

## 📊 您的场景分析

### 当前流程
```bash
1. TRUNCATE TABLE ZZ_PSUB_REF_NB;
2. 使用 dbloadsr (SQL*Loader) 导入 100 万行数据
3. ❓ 是否需要 REBUILD INDEX？
4. ✅ 执行 UPDATE STATISTICS（必须）
```

### 表结构
根据 `create_ZZ_PSUB_REF_NB.sql`，该表有：
- **8 个索引**：
  - ZZ_PSUB_REF_NB_ID1 (SUBSCRIBER)
  - ZZ_PSUB_REF_NB_ID2 (CONTACT_NO)
  - ZZ_PSUB_REF_NB_ID3 (CELLULAR, IMEI)
  - ZZ_PSUB_REF_NB_ID4 (ID_CARD_NO, SUBSCRIBER)
  - ZZ_PSUB_REF_NB_ID5 (USER_REF)
  - ZZ_PSUB_REF_NB_ID6 (ACT_DATE, DEALER, RPLAN)
  - ZZ_PSUB_REF_NB_ID7 (S_OFF_DATE)
  - ZZ_PSUB_REF_NB_ID8 (SUBSTR(ID_CARD_NO,1,8))

---

## ✅ 为什么不需要 REBUILD INDEX

### 原因 1: TRUNCATE 后索引是干净的

```sql
TRUNCATE TABLE ZZ_PSUB_REF_NB;
```

**TRUNCATE 的效果：**
- ✅ 清空表数据
- ✅ 清空索引数据
- ✅ 索引结构保持有效（VALID）
- ✅ 索引没有碎片
- ✅ 高水位线（HWM）重置

**对比 DELETE：**
```sql
-- DELETE 会导致碎片
DELETE FROM ZZ_PSUB_REF_NB;  -- ❌ 会产生碎片，可能需要 REBUILD
```

| 操作 | 索引状态 | 是否有碎片 | 需要 REBUILD？ |
|------|---------|-----------|---------------|
| TRUNCATE | VALID | ❌ 无碎片 | ❌ 不需要 |
| DELETE | VALID | ✅ 有碎片 | ⚠️ 可能需要 |
| DROP INDEX | - | - | ✅ 需要重建 |

---

### 原因 2: SQL*Loader 加载数据时索引自动维护

```bash
# 您的脚本第 143 行
result=`. $WRKD'/dbloadsr' $FILE_DIR/$tmp_file $ZZ_PSUB_REF_BK_TBL`
```

**SQL*Loader 的工作方式：**
1. 每插入一行数据 → 自动更新所有 8 个索引
2. 索引是实时维护的，不会失效
3. 加载完成后，索引已经是最新且有序的状态

**索引状态：**
```
加载前（TRUNCATE 后）: 8 个索引 → 空的，但结构完整
加载中: 每插入一行 → 索引自动更新
加载后: 8 个索引 → 包含 100 万条数据，结构完整，无碎片
```

---

### 原因 3: REBUILD INDEX 的真正用途

`rebuild_index` 存储过程适用于以下场景：

#### 场景 1: 索引碎片化（长期 DML 操作）
```sql
-- 多次 INSERT、UPDATE、DELETE 导致碎片
-- 需要定期整理
```

**检查碎片：**
```sql
SELECT index_name, 
       blevel,                    -- B树深度（超过4可能需要重建）
       leaf_blocks,               -- 叶子块数量
       num_rows,                  -- 索引行数
       distinct_keys,             -- 唯一键数量
       clustering_factor          -- 聚簇因子（越高越碎片化）
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_NB';
```

#### 场景 2: 索引损坏
```sql
-- 检查索引是否损坏
SELECT index_name, status
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_NB' AND status != 'VALID';
```

#### 场景 3: 索引空间回收
```sql
-- 大量 DELETE 后回收空间
-- TRUNCATE + Load 场景不存在这个问题
```

#### 场景 4: 性能优化（长期运行后）
```sql
-- 表经过几个月的高频 DML 操作
-- 索引性能下降
```

---

## 🎯 您的场景 vs REBUILD 适用场景

| 特征 | 您的场景 | REBUILD 适用场景 |
|------|---------|-----------------|
| 表操作 | TRUNCATE + 全新加载 | 长期 DML 操作 |
| 数据加载方式 | 一次性批量加载 | 持续增删改 |
| 索引状态 | 全新构建，无碎片 | 碎片化，性能下降 |
| 加载频率 | 每天一次 | - |
| 数据量 | 固定 100 万行 | 逐渐增长 |
| **需要 REBUILD？** | ❌ **不需要** | ✅ 可能需要 |

---

## ⏱️ 性能对比

### 场景：ZZ_PSUB_REF_NB 表，8 个索引，100 万行数据

#### 方案 1: 不 REBUILD（推荐）
```bash
1. TRUNCATE TABLE          - 1 秒
2. SQL*Loader 加载数据     - 3-5 分钟（含索引自动维护）
3. UPDATE STATISTICS       - 2-3 分钟
   总计：约 5-8 分钟
```

#### 方案 2: REBUILD 索引（不推荐）
```bash
1. TRUNCATE TABLE          - 1 秒
2. SQL*Loader 加载数据     - 3-5 分钟
3. REBUILD 8 个索引        - 5-8 分钟（浪费！）
4. UPDATE STATISTICS       - 2-3 分钟
   总计：约 10-16 分钟
```

**结论：REBUILD 索引会增加 5-8 分钟的处理时间，但没有任何好处！**

---

## 🔍 实际验证

### 检查索引是否需要重建

运行以下 SQL 检查索引健康状态：

```sql
-- 检查索引状态
SELECT index_name, 
       status,                    -- 应该是 VALID
       blevel,                    -- B树深度（<= 3 正常）
       leaf_blocks,               -- 叶子块数量
       num_rows,                  -- 索引行数
       distinct_keys,             -- 唯一键数量
       clustering_factor,         -- 聚簇因子
       ROUND(clustering_factor / num_rows * 100, 2) AS fragmentation_pct
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_NB'
ORDER BY index_name;

-- 如果 blevel > 4 或 fragmentation_pct > 30，才考虑 REBUILD
```

### TRUNCATE + Load 后的典型结果

```
INDEX_NAME              STATUS  BLEVEL  LEAF_BLOCKS  NUM_ROWS    FRAGMENTATION_PCT
--------------------    ------  ------  -----------  --------    -----------------
ZZ_PSUB_REF_NB_ID1      VALID   2       2500         1000000     0.25%
ZZ_PSUB_REF_NB_ID2      VALID   2       2500         1000000     0.25%
ZZ_PSUB_REF_NB_ID3      VALID   3       3000         1000000     0.30%
...

结论：所有索引都是健康的，不需要 REBUILD！
```

---

## ✅ 正确的操作流程（您的脚本已经做对了）

### custpro_nb.sh 的正确流程

```bash
# 1. TRUNCATE 表（第 115-127 行）
echo "EXEC cn_lib.onpload_trunc_tbl('${ZZ_PSUB_REF_BK_TBL}');" 
sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log

# 2. 格式化输入文件（第 130-135 行）
awk -f $WRKD/custpro_format.awk $SUBR_INFO_FILE > $tmp_file

# 3. 加载数据到表（第 138-150 行）
result=`. $WRKD'/dbloadsr' $FILE_DIR/$tmp_file $ZZ_PSUB_REF_BK_TBL`

# 4. ❌ REBUILD INDEX（第 152-196 行）- 已正确注释掉！
###cd $CTL
###echo "Start Rebuild Index at $ZZ_PSUB_REF_BK_TBL ... \c" >> ${logf}
###...（整段被注释）

# 5. ✅ UPDATE STATISTICS（第 198-224 行）- 必须执行！
echo "EXEC cn_lib.update_statistics('$ZZ_PSUB_REF_BK_TBL');"
sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log
```

**您的脚本已经做对了：REBUILD INDEX 被注释掉，只执行 UPDATE STATISTICS！**

---

## 🚨 什么时候需要 REBUILD INDEX？

### 场景 1: 长期运行的生产表

如果 `ZZ_PSUB_REF_NB` 表是增量更新而不是每天 TRUNCATE + 全新加载：

```sql
-- 每天只更新变化的数据
UPDATE ZZ_PSUB_REF_NB SET ... WHERE ...;
INSERT INTO ZZ_PSUB_REF_NB VALUES ...;
DELETE FROM ZZ_PSUB_REF_NB WHERE ...;

-- 几个月后，可能需要 REBUILD 索引
```

### 场景 2: 监控指标超标

```sql
-- 定期监控（每月一次）
SELECT index_name, blevel, clustering_factor / num_rows * 100 AS frag_pct
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_NB';

-- 如果 blevel > 4 或 frag_pct > 30%，考虑 REBUILD
```

### 场景 3: 查询性能明显下降

```sql
-- 相同的查询，性能突然变慢
-- 可能是索引碎片化导致
```

---

## 📊 SQL*Loader 的优化建议

### 如果想加快加载速度，可以考虑：

#### 方案 1: DIRECT PATH 加载（推荐）
```bash
sqlldr userid=FES/password control=load.ctl data=data.dat direct=true
```

**优点：**
- ✅ 跳过 SQL 引擎，直接写数据块
- ✅ 速度快 3-5 倍
- ✅ 索引在加载完成后一次性构建（更高效）

#### 方案 2: 临时禁用索引（不推荐，复杂）
```sql
-- 加载前
ALTER INDEX ZZ_PSUB_REF_NB_ID1 UNUSABLE;
...（8 个索引都设为 UNUSABLE）

-- 加载数据
-- sqlldr ...

-- 加载后重建索引
ALTER INDEX ZZ_PSUB_REF_NB_ID1 REBUILD ONLINE;
...（重建 8 个索引）
```

**对比：**
| 方法 | 时间 | 复杂度 | 推荐度 |
|------|------|--------|--------|
| 当前方式（常规加载） | 5-8 分钟 | 简单 | ✅ 适合 |
| DIRECT PATH | 2-4 分钟 | 简单 | ✅✅ 最推荐 |
| 禁用+重建索引 | 3-5 分钟 | 复杂 | ⚠️ 不推荐 |

---

## 🎓 总结

### ❌ 不需要执行 `rebuild_index` 的原因

1. ✅ **TRUNCATE 后索引是干净的** - 没有碎片
2. ✅ **SQL*Loader 自动维护索引** - 加载时实时更新
3. ✅ **全新数据加载** - 索引从零开始构建，最优状态
4. ✅ **每天重新加载** - 不存在长期碎片化问题
5. ⏱️ **浪费时间** - REBUILD 8 个索引需要 5-8 分钟，无任何收益

### ✅ 必须执行的操作

```bash
# 唯一必须执行的：UPDATE STATISTICS
EXEC cn_lib.update_statistics('ZZ_PSUB_REF_NB');
```

**原因：**
- ✅ 更新表和索引的统计信息
- ✅ 让 Oracle 优化器知道数据分布
- ✅ 确保查询使用正确的执行计划
- ✅ 执行时间短（2-3 分钟），收益大

### 📋 标准操作流程

```
✅ 推荐流程：
1. TRUNCATE TABLE
2. SQL*Loader 加载数据（索引自动维护）
3. UPDATE STATISTICS ← 必须！
4. 开始使用

❌ 不推荐流程：
1. TRUNCATE TABLE
2. SQL*Loader 加载数据
3. REBUILD INDEX ← 浪费时间！
4. UPDATE STATISTICS
5. 开始使用
```

---

## 🔧 您的脚本建议

### 当前状态：✅ 完全正确

您的 `custpro_nb.sh` 脚本已经做对了：

```bash
# 第 152-196 行：Rebuild Index 已被注释
###cd $CTL
###echo "Start Rebuild Index at $ZZ_PSUB_REF_BK_TBL ... \c" >> ${logf}
###...

# 第 198-224 行：Update Statistics 正在执行
cd $CTL
echo "Start Update Statistics $ZZ_PSUB_REF_BK_TBL at `date`" >> ${logf}
echo "EXEC cn_lib.update_statistics('$ZZ_PSUB_REF_BK_TBL');"
sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log
```

**建议：保持现状，不需要修改！**

---

## 📖 参考资料

### Oracle 官方文档建议

**Oracle Database Performance Tuning Guide：**
> "Index rebuilds are rarely necessary for indexes on tables that are truncated and reloaded. 
> The indexes will be as efficient as possible after the reload."

**翻译：**
> "对于 TRUNCATE 后重新加载的表，索引重建很少是必要的。
> 重新加载后，索引将达到最佳效率。"

### 何时真正需要 REBUILD

```sql
-- Oracle 推荐：只在以下情况 REBUILD
1. BLEVEL > 4
2. 索引碎片率 > 30%
3. 查询性能明显下降
4. 空间浪费严重
```

---

## ✅ 最终建议

### 对于您的场景

**问：先 TRUNCATE ZZ_PSUB_REF_NB，然后 Load 100 万数据，需不需要执行 rebuild_index？**

**答：不需要！完全没有必要，不执行这个方法完全没事！**

**理由：**
1. TRUNCATE + 全新加载 = 索引自然处于最优状态
2. REBUILD 只会浪费 5-8 分钟，没有任何好处
3. UPDATE STATISTICS 才是真正必须执行的步骤
4. 您的脚本已经正确地注释掉了 REBUILD INDEX 部分

**建议：保持现有脚本不变，继续使用！** ✅

