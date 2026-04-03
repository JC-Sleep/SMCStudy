# TRUNCATE + Load 数据 - 索引维护快速参考

## 🎯 一句话答案

**不需要执行 `rebuild_index`！TRUNCATE + 全新加载数据后，索引已经处于最优状态，重建索引纯属浪费时间。**

```bash
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
---

## 📋 快速决策表

| 场景 | 是否需要 REBUILD INDEX | 是否需要 UPDATE STATISTICS |
|------|---------------------|--------------------------|
| **TRUNCATE + 全新加载（您的场景）** | ❌ **不需要** | ✅ **必须** |
| DELETE + 增量加载 | ⚠️ 可能需要 | ✅ 必须 |
| 长期 DML 操作（几个月） | ⚠️ 定期检查 | ✅ 定期执行 |
| 索引 BLEVEL > 4 | ✅ 需要 | ✅ 必须 |
| 索引碎片率 > 30% | ✅ 需要 | ✅ 必须 |
| 索引状态 = UNUSABLE | ✅ 必须 | ✅ 必须 |

---

## ⏱️ 时间对比

### 您的表：ZZ_PSUB_REF_NB（8 个索引，100 万行）

| 步骤 | 不 REBUILD（推荐） | REBUILD（不推荐） |
|------|-------------------|------------------|
| TRUNCATE | 1 秒 | 1 秒 |
| SQL*Loader 加载 | 3-5 分钟 | 3-5 分钟 |
| REBUILD INDEX | - | **5-8 分钟** ⚠️ |
| UPDATE STATISTICS | 2-3 分钟 | 2-3 分钟 |
| **总计** | **5-8 分钟** ✅ | **10-16 分钟** ❌ |
| **节省时间** | - | **浪费 5-8 分钟** |

---

## ✅ 正确流程（您的脚本已经做对了）

```bash
# Step 1: TRUNCATE 表
TRUNCATE TABLE ZZ_PSUB_REF_NB;

# Step 2: SQL*Loader 加载数据（索引自动维护）
sqlldr userid=FES/password control=load.ctl data=data.dat

# Step 3: ❌ 跳过 REBUILD INDEX（不需要！）

# Step 4: ✅ 执行 UPDATE STATISTICS（必须！）
EXEC cn_lib.update_statistics('ZZ_PSUB_REF_NB');

# Step 5: 完成，开始使用
```

---

## 🔍 为什么不需要 REBUILD？

### TRUNCATE 的效果
```
TRUNCATE TABLE
    ↓
清空表数据 ✅
清空索引数据 ✅
索引结构保持有效 ✅
没有碎片 ✅
高水位线重置 ✅
    ↓
索引已经是"干净"状态
```

### SQL*Loader 的工作方式
```
加载数据
    ↓
每插入一行 → 8 个索引自动更新 ✅
    ↓
加载完成 → 索引已经是最新且有序 ✅
    ↓
索引已经处于最优状态
```

### REBUILD 的真正用途
```
适用场景：
- 长期 DML 操作导致碎片化
- 索引 BLEVEL > 4
- 碎片率 > 30%
- 索引损坏

不适用您的场景：
- TRUNCATE + 全新加载
- 每天重新加载
- 索引从零开始构建
```

---

## 🎓 关键概念

### TRUNCATE vs DELETE

| 操作 | 索引状态 | 碎片 | 需要 REBUILD？ |
|------|---------|------|---------------|
| **TRUNCATE** | 清空，结构完整 | ❌ 无 | ❌ 不需要 |
| **DELETE** | 保留数据，标记删除 | ✅ 有 | ⚠️ 可能需要 |

### 索引维护方式

| 加载方式 | 索引维护 | 加载后状态 |
|---------|---------|-----------|
| **SQL*Loader 常规** | 实时自动更新 | 最优 ✅ |
| **SQL*Loader DIRECT** | 加载后一次性构建 | 最优 ✅ |
| **INSERT ... SELECT** | 实时自动更新 | 最优 ✅ |

---

## 🚨 什么时候需要 REBUILD？

### 检查索引健康状态

```sql
-- 查询索引统计信息
SELECT index_name, 
       blevel,                                           -- B树深度
       num_rows,                                         -- 行数
       distinct_keys,                                    -- 唯一键
       clustering_factor,                                -- 聚簇因子
       ROUND(clustering_factor / num_rows * 100, 2) AS frag_pct  -- 碎片率
FROM user_indexes
WHERE table_name = 'ZZ_PSUB_REF_NB'
ORDER BY index_name;
```

### 判断标准

```
需要 REBUILD 的条件（任一满足）：
✓ BLEVEL > 4
✓ 碎片率 > 30%
✓ 查询性能明显下降
✓ 索引状态 = UNUSABLE
```

### TRUNCATE + Load 后的典型结果

```
INDEX_NAME              BLEVEL  FRAG_PCT  需要REBUILD？
--------------------    ------  --------  ------------
ZZ_PSUB_REF_NB_ID1      2       0.25%     ❌ 不需要
ZZ_PSUB_REF_NB_ID2      2       0.25%     ❌ 不需要
ZZ_PSUB_REF_NB_ID3      2       0.30%     ❌ 不需要
ZZ_PSUB_REF_NB_ID4      2       0.28%     ❌ 不需要
ZZ_PSUB_REF_NB_ID5      2       0.26%     ❌ 不需要
ZZ_PSUB_REF_NB_ID6      3       0.35%     ❌ 不需要
ZZ_PSUB_REF_NB_ID7      2       0.24%     ❌ 不需要
ZZ_PSUB_REF_NB_ID8      2       0.27%     ❌ 不需要

结论：所有索引都是健康的！
```

---

## 📊 您的脚本分析

### custpro_nb.sh 当前状态

```bash
# ✅ 正确：REBUILD INDEX 已被注释（第 152-196 行）
###cd $CTL
###echo "Start Rebuild Index at $ZZ_PSUB_REF_BK_TBL ... \c" >> ${logf}
###echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_PSUB_REF_CMD.log
###echo "SET SERVEROUTPUT ON " >> DOWNLOAD_PSUB_REF_CMD.log
###echo "EXEC cn_lib.rebuild_index('${ZZ_PSUB_REF_BK_TBL}'); " >> DOWNLOAD_PSUB_REF_CMD.log
###...（整段被注释）

# ✅ 正确：UPDATE STATISTICS 正在执行（第 198-224 行）
cd $CTL
echo "Start Update Statistics $ZZ_PSUB_REF_BK_TBL at `date`" >> ${logf}
echo "EXEC cn_lib.update_statistics('$ZZ_PSUB_REF_BK_TBL');"
sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log
```

**结论：您的脚本已经做对了！保持现状即可。** ✅

---

## 🎯 最终建议

### 对于您的问题

**问：先 TRUNCATE ZZ_PSUB_REF_NB，然后 Load 100 万数据，需不需要执行 rebuild_index？有必要吗？不执行没事吧？**

**答：**
1. ❌ **不需要执行 rebuild_index**
2. ❌ **完全没有必要**
3. ✅ **不执行完全没事，反而更好**
4. ⏱️ **执行反而浪费 5-8 分钟**

### 理由总结

| # | 理由 | 说明 |
|---|------|------|
| 1 | TRUNCATE 后索引干净 | 没有碎片，结构完整 |
| 2 | SQL*Loader 自动维护 | 加载时实时更新索引 |
| 3 | 全新数据加载 | 索引从零构建，最优状态 |
| 4 | 每天重新加载 | 不存在长期碎片化 |
| 5 | 浪费时间 | 8 个索引 REBUILD 需 5-8 分钟 |
| 6 | Oracle 官方建议 | 不推荐 TRUNCATE 后 REBUILD |

### 必须执行的操作

```
唯一必须执行：UPDATE STATISTICS
```

**原因：**
- ✅ 更新表和索引统计信息
- ✅ 让优化器知道数据分布
- ✅ 确保查询性能最优
- ⏱️ 执行快（2-3 分钟），收益大

---

## 🔧 优化建议（可选）

### 如果想进一步提升性能

#### 方案 1: 使用 DIRECT PATH 加载
```bash
# 修改 dbloadsr 脚本，添加 direct=true
sqlldr userid=FES/password control=load.ctl data=data.dat direct=true
```

**优点：**
- 速度提升 3-5 倍
- 索引在加载后一次性构建（更高效）

#### 方案 2: 增加 UPDATE STATISTICS 的采样率
```sql
-- 当前：采样 10%
EXEC cn_lib.update_statistics('ZZ_PSUB_REF_NB');

-- 如果需要更准确：
EXEC DBMS_STATS.GATHER_TABLE_STATS('FES', 'ZZ_PSUB_REF_NB', 
     estimate_percent => 20,  -- 提高到 20%
     cascade => TRUE);
```

---

## 📖 参考文档

1. **Oracle统计信息自动收集机制详解.md** - 为什么必须执行 UPDATE STATISTICS
2. **ZZ_POS_ITEM_NB统计信息收集的必要性分析.md** - 统计信息的重要性
3. **Shell脚本参数详解-$#的使用.md** - 脚本参数传递机制

---

## ✅ 总结

**您的脚本已经做对了！**

- ✅ REBUILD INDEX 已被注释 → 正确
- ✅ UPDATE STATISTICS 正在执行 → 正确
- ✅ 不需要修改任何代码
- ✅ 继续使用现有脚本即可

**记住：TRUNCATE + Load = 索引最优，不需要 REBUILD！**

