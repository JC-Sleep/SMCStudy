# Shell 脚本参数详解 - `$#` 的使用

## 📚 Shell 特殊变量

### `$#` - 参数个数

`$#` 是 Shell 的**特殊内置变量**，表示传递给脚本的**参数个数**（不包括脚本名本身）。

### 其他常用特殊变量

| 变量 | 含义 | 示例 |
|------|------|------|
| `$0` | 脚本名称 | `./download_custpro_cmd_nb.sh` |
| `$1` | 第1个参数 | 如果执行 `./script.sh abc`，则 `$1 = abc` |
| `$2` | 第2个参数 | 如果执行 `./script.sh abc 123`，则 `$2 = 123` |
| `$#` | **参数个数** | 如果执行 `./script.sh abc 123`，则 `$# = 2` |
| `$@` | 所有参数列表 | `"$1" "$2" "$3" ...` |
| `$*` | 所有参数（作为单个字符串） | `"$1 $2 $3 ..."` |
| `$?` | 上一个命令的退出状态 | `0` 表示成功，非0表示失败 |

---

## 🔍 您的代码分析

### 代码段
```bash
if test $# != 0 -a $# != 1
then
   echo "No. of parameter error"
   exit
fi

## Rerun Codes ######################################
if [ $# -eq 0 ]
then
   rerun=0
   # ... 正常执行逻辑
else
   rerun=1
   # ... 重新运行逻辑
fi
```

---

## 📖 逐行解释

### 第一段：参数个数验证
```bash
if test $# != 0 -a $# != 1
```

**含义：**
- `test` 命令用于条件判断（等同于 `[` 命令）
- `$# != 0` - 参数个数不等于 0
- `-a` - 逻辑 AND（并且）
- `$# != 1` - 参数个数不等于 1
- **整体意思：** 如果参数个数既不等于0，也不等于1

**翻译成人话：**
```
如果 (参数个数 != 0) AND (参数个数 != 1)，即参数个数 >= 2
那么：
   输出 "参数个数错误"
   退出脚本
```

**允许的情况：**
- ✅ 参数个数 = 0（无参数）
- ✅ 参数个数 = 1（一个参数）
- ❌ 参数个数 >= 2（两个或更多参数） → 报错退出

---

### 第二段：根据参数个数决定运行模式
```bash
if [ $# -eq 0 ]
then
   rerun=0
   # 正常模式
else
   rerun=1
   # Rerun 模式（参数个数 = 1）
fi
```

**含义：**
- `-eq` - 数值相等判断（equal）
- 如果 `$# = 0`（无参数），设置 `rerun=0`，走正常执行流程
- 否则（`$# = 1`，有一个参数），设置 `rerun=1`，走重新运行流程

---

## 💡 实际执行示例

### 示例 1: 无参数执行（正常模式）
```bash
$ ./download_custpro_cmd_nb.sh
```

**执行流程：**
```
$# = 0
第一段检查: 0 != 0 AND 0 != 1 → FALSE AND TRUE → FALSE（通过检查）
第二段判断: $# == 0 → TRUE
结果: rerun=0，正常执行模式
```

**日志输出：**
```
download_custpro_cmd: Start at [当前时间]
```

---

### 示例 2: 一个参数执行（Rerun 模式）
```bash
$ ./download_custpro_cmd_nb.sh rerun
```

**执行流程：**
```
$# = 1（有一个参数 "rerun"）
第一段检查: 1 != 0 AND 1 != 1 → TRUE AND FALSE → FALSE（通过检查）
第二段判断: $# == 0 → FALSE，走 else 分支
结果: rerun=1，重新运行模式
```

**日志输出：**
```
RERUN download_custpro_cmd: Start at [当前时间]
```

**实际上这个脚本不关心参数的内容，只关心参数的个数！**

---

### 示例 3: 两个或更多参数（错误）
```bash
$ ./download_custpro_cmd_nb.sh param1 param2
```

**执行流程：**
```
$# = 2（有两个参数）
第一段检查: 2 != 0 AND 2 != 1 → TRUE AND TRUE → TRUE（检查失败）
输出: "No. of parameter error"
退出脚本
```

---

## 🎯 脚本设计意图

### 该脚本的参数设计

```
download_custpro_cmd_nb.sh [参数]

用法：
  无参数     - 正常执行模式（首次运行）
  一个参数   - Rerun 重新运行模式（重新执行）
  多个参数   - 不支持，报错退出
```

### 两种模式的区别

#### 模式 1: 正常模式（$# = 0）
```bash
rerun=0
logf=$logd/download_custpro_cmd_${date_pattern}.log
echo "download_custpro_cmd: Start at `date`" > ${logf}
# 从头开始完整执行：
# 1. 从 Geneva 下载文件
# 2. 加载数据到 ZZ_PSUB_REF
# 3. 执行 custpro_nb.sh
```

#### 模式 2: Rerun 模式（$# = 1）
```bash
rerun=1
rerun_file=${WRKD}"/rerun_cust.log"
logf=$logd/rerun_download_custpro_cmd_${date_pattern}.log
echo "RERUN download_custpro_cmd: Start at `date`" > ${logf}
# 重新运行模式：
# 1. 检查是否已经有文件
# 2. 只创建 rerun 标记文件
# 3. 跳过某些步骤
```

---

## 📝 代码结构总结

```
┌─────────────────────────────────────┐
│ 脚本启动                             │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 检查参数个数: $#                     │
│ - 允许 0 或 1 个参数                 │
│ - 其他个数报错退出                   │
└──────────────┬──────────────────────┘
               │
               ▼
         ┌─────┴─────┐
         │           │
    $# = 0       $# = 1
    (无参数)     (一个参数)
         │           │
         ▼           ▼
   ┌─────────┐ ┌─────────┐
   │正常模式 │ │Rerun模式│
   │rerun=0  │ │rerun=1  │
   └────┬────┘ └────┬────┘
        │           │
        ▼           ▼
   完整执行     重新运行
   所有步骤     (跳过部分步骤)
```

---

## 🛠️ 测试命令

### 测试参数个数检查

```bash
# 测试 1: 无参数（应该成功）
./download_custpro_cmd_nb.sh
echo "退出码: $?"

# 测试 2: 一个参数（应该成功）
./download_custpro_cmd_nb.sh rerun
echo "退出码: $?"

# 测试 3: 两个参数（应该报错）
./download_custpro_cmd_nb.sh param1 param2
echo "退出码: $?"
# 输出: No. of parameter error
```

### 查看脚本接收到的参数

在脚本开头添加调试信息：
```bash
#!/usr/bin/ksh
echo "脚本名: $0"
echo "参数个数: $#"
echo "第一个参数: $1"
echo "第二个参数: $2"
echo "所有参数: $@"
```

---

## 🔧 实际业务场景

### 场景 1: 每日定时任务（Cron Job）
```bash
# crontab -e
# 每天凌晨 2:00 自动执行（无参数，正常模式）
0 2 * * * /path/to/download_custpro_cmd_nb.sh
```

### 场景 2: 手动重新运行
```bash
# 如果今天的任务失败了，需要手动重新运行
$ ./download_custpro_cmd_nb.sh rerun

# 脚本会：
# 1. 检查控制文件是否存在
# 2. 检查上次 DDL 更新日期
# 3. 如果没问题，创建 rerun_cust.log 标记文件
# 4. 由 custpro_nb.sh 检测到这个文件后重新处理
```

---

## ⚠️ 常见错误

### 错误 1: 传递多个参数
```bash
$ ./download_custpro_cmd_nb.sh param1 param2
No. of parameter error
```

**原因：** 脚本只支持 0 或 1 个参数

### 错误 2: 误解参数内容
```bash
# ❌ 错误理解：以为参数内容有特殊含义
$ ./download_custpro_cmd_nb.sh abc
# 实际上，参数内容是什么不重要，只要有一个参数就行

# ✅ 正确理解：只看参数个数
$ ./download_custpro_cmd_nb.sh anything
# 效果完全一样，都是触发 rerun 模式
```

---

## 📊 与其他脚本的对比

### 常见参数处理模式

#### 模式 1: 固定参数个数（本脚本）
```bash
if [ $# -ne 2 ]; then
    echo "Usage: $0 <param1> <param2>"
    exit 1
fi
```

#### 模式 2: 可选参数
```bash
if [ $# -eq 0 ]; then
    echo "使用默认值"
else
    param1=$1
fi
```

#### 模式 3: 参数解析（getopt）
```bash
while getopts "hv:f:" opt; do
    case $opt in
        h) show_help ;;
        v) version=$OPTARG ;;
        f) file=$OPTARG ;;
    esac
done
```

---

## ✅ 总结

### `$#` 的核心要点

1. **`$#` 是内置变量**，不是命令执行结果
2. **表示参数个数**，不包括脚本名 `$0`
3. **只统计个数**，不关心参数内容

### 该脚本的参数逻辑

```
参数个数 = 0  → 正常执行模式（rerun=0）
参数个数 = 1  → 重新运行模式（rerun=1）
参数个数 >= 2 → 报错退出
```

### 实际使用

```bash
# 正常执行（定时任务）
./download_custpro_cmd_nb.sh

# 重新运行（手动修复）
./download_custpro_cmd_nb.sh rerun
```

---

## 🔍 扩展阅读

### 相关 Shell 特殊变量速查表

```bash
$0    脚本名称
$1-$9 位置参数（第1到第9个参数）
${10} 第10个参数及以后（需要用花括号）
$#    参数个数
$@    所有参数（作为独立字符串）
$*    所有参数（作为单个字符串）
$?    上一个命令的退出状态
$$    当前脚本的进程ID
$!    最后一个后台进程的进程ID
```

### 测试脚本示例

创建测试脚本 `test_params.sh`：
```bash
#!/bin/bash
echo "脚本名: $0"
echo "参数个数: $#"
echo "参数列表: $@"
echo "第1个参数: $1"
echo "第2个参数: $2"
echo "第3个参数: $3"

if [ $# -eq 0 ]; then
    echo "无参数模式"
elif [ $# -eq 1 ]; then
    echo "单参数模式"
else
    echo "多参数模式"
fi
```

测试执行：
```bash
$ chmod +x test_params.sh

$ ./test_params.sh
参数个数: 0
无参数模式

$ ./test_params.sh abc
参数个数: 1
第1个参数: abc
单参数模式

$ ./test_params.sh abc 123 xyz
参数个数: 3
参数列表: abc 123 xyz
第1个参数: abc
第2个参数: 123
第3个参数: xyz
多参数模式
```

