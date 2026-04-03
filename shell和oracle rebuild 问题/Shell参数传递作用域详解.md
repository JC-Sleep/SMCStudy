# Shell 脚本参数传递作用域详解

## 🎯 核心结论

**B.sh 中的 `$#` 是指 A 调用 B 时传递给 B 的参数个数，不是 A.sh 接收的参数！**

### 关键原则
- 每个脚本都有**独立的参数上下文**
- `$#`、`$1`、`$2` 等变量是**脚本级别的局部变量**
- 子脚本不会自动继承父脚本的参数

---

## 📊 参数传递规则

### 规则 1: 参数不会自动传递
```bash
# A.sh 执行时：./A.sh param1 param2 param3
# A.sh 中:
$# = 3
$1 = param1
$2 = param2
$3 = param3

# A.sh 调用 B.sh 不传参数
./B.sh

# B.sh 中:
$# = 0  # 不是 3！
$1 = (空)
$2 = (空)
```

### 规则 2: 需要显式传递参数
```bash
# A.sh 中调用 B.sh 时显式传递所有参数
./B.sh "$@"

# B.sh 中:
$# = 3  # 现在是 3
$1 = param1
$2 = param2
$3 = param3
```

### 规则 3: 可以选择性传递
```bash
# A.sh 只传递第 1 个参数
./B.sh "$1"

# B.sh 中:
$# = 1
$1 = param1  # A 的第 1 个参数
$2 = (空)
```

### 规则 4: 可以传递新参数
```bash
# A.sh 传递固定参数
./B.sh "hello" "world"

# B.sh 中:
$# = 2
$1 = hello
$2 = world
# 与 A.sh 的参数完全无关
```

---

## 🔍 实际测试案例

### 测试执行
```bash
# 给 A.sh 传递 3 个参数
$ ./test_A.sh AAA BBB CCC
```

### 预期输出分析

```
========== A.sh 脚本开始 ==========
A.sh 接收的参数个数: 3
A.sh 接收的参数列表: AAA BBB CCC
A.sh 的第1个参数: AAA
A.sh 的第2个参数: BBB
A.sh 的第3个参数: CCC

========== 场景1: A 调用 B 时不传参数 ==========
  [B.sh] 接收的参数个数: 0       ← B.sh 的 $# = 0（不是 3！）
  [B.sh] 接收的参数列表: 
  [B.sh] 第1个参数: 
  [B.sh] 第2个参数: 
  [B.sh] 第3个参数: 
  [B.sh] 第4个参数: 

========== 场景2: A 调用 B 时传递固定参数 ==========
  [B.sh] 接收的参数个数: 2       ← B.sh 的 $# = 2
  [B.sh] 接收的参数列表: hello world
  [B.sh] 第1个参数: hello        ← 不是 AAA
  [B.sh] 第2个参数: world        ← 不是 BBB
  [B.sh] 第3个参数: 
  [B.sh] 第4个参数: 

========== 场景3: A 调用 B 时只传递 A 的第1个参数 ==========
  [B.sh] 接收的参数个数: 1       ← B.sh 的 $# = 1（不是 3！）
  [B.sh] 接收的参数列表: AAA
  [B.sh] 第1个参数: AAA          ← A 的第 1 个参数
  [B.sh] 第2个参数: 
  [B.sh] 第3个参数: 
  [B.sh] 第4个参数: 

========== 场景4: A 调用 B 时传递 A 的所有参数 ==========
  [B.sh] 接收的参数个数: 3       ← B.sh 的 $# = 3
  [B.sh] 接收的参数列表: AAA BBB CCC
  [B.sh] 第1个参数: AAA
  [B.sh] 第2个参数: BBB
  [B.sh] 第3个参数: CCC
  [B.sh] 第4个参数: 

========== 场景5: A 调用 B 时传递混合参数 ==========
  [B.sh] 接收的参数个数: 4       ← B.sh 的 $# = 4
  [B.sh] 接收的参数列表: prefix AAA BBB suffix
  [B.sh] 第1个参数: prefix
  [B.sh] 第2个参数: AAA          ← A 的第 1 个参数
  [B.sh] 第3个参数: BBB          ← A 的第 2 个参数
  [B.sh] 第4个参数: suffix

========== A.sh 脚本结束 ==========
```

---

## 📋 常见调用模式

### 模式 1: 不传递参数（B.sh 独立运行）
```bash
# A.sh
./B.sh

# B.sh 中
$# = 0
```

**使用场景：** B.sh 是独立的工具脚本，不需要参数

---

### 模式 2: 传递固定参数
```bash
# A.sh
date_str=$(date +%Y%m%d)
./B.sh "$date_str" "process_data"

# B.sh 中
$# = 2
$1 = 20260403  # 日期
$2 = process_data  # 操作类型
```

**使用场景：** B.sh 需要特定的参数，由 A.sh 计算或生成

---

### 模式 3: 透传所有参数
```bash
# A.sh
./B.sh "$@"

# 如果执行 ./A.sh param1 param2
# B.sh 中
$# = 2
$1 = param1
$2 = param2
```

**使用场景：** B.sh 是 A.sh 的包装器（wrapper），参数直接透传

---

### 模式 4: 透传部分参数
```bash
# A.sh
# 跳过第 1 个参数，传递剩余参数
shift
./B.sh "$@"

# 如果执行 ./A.sh skip_this param1 param2
# B.sh 中
$# = 2
$1 = param1  # A 的第 2 个参数
$2 = param2  # A 的第 3 个参数
```

**使用场景：** A.sh 使用第 1 个参数，其余参数传给 B.sh

---

### 模式 5: 混合参数
```bash
# A.sh
log_file="/var/log/process.log"
./B.sh "$log_file" "$1" "$2"

# 如果执行 ./A.sh data1 data2
# B.sh 中
$# = 3
$1 = /var/log/process.log  # A 生成的参数
$2 = data1  # A 的第 1 个参数
$3 = data2  # A 的第 2 个参数
```

**使用场景：** 组合固定参数和动态参数

---

## 🎓 您的实际脚本分析

### download_custpro_cmd_nb.sh 调用 custpro_nb.sh

#### download_custpro_cmd_nb.sh (A脚本)
```bash
# 接收参数
if [ $# -eq 0 ]
then
   rerun=0
else
   rerun=1
fi

# 调用 custpro_nb.sh
${SRCAPPL}/custpro_nb.sh ${rerun} ${rerun_file} ${today} ${date_pattern} >> ${logf}
```

#### custpro_nb.sh (B脚本)
```bash
# B脚本中的参数
$# = 4  # 不管 A 接收了多少参数
$1 = ${rerun}         # 0 或 1
$2 = ${rerun_file}    # 文件路径
$3 = ${today}         # 日期
$4 = ${date_pattern}  # 日期格式
```

**关键点：**
- B 脚本的 `$#` 永远是 4（A 传递了 4 个参数）
- 与 A 脚本接收的参数个数（0 或 1）无关

---

## 📊 参数作用域可视化

```
┌─────────────────────────────────────────────┐
│ 用户执行: ./A.sh param1 param2 param3      │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │   A.sh 的参数空间    │
         │   $# = 3            │
         │   $1 = param1       │
         │   $2 = param2       │
         │   $3 = param3       │
         └──────────┬───────────┘
                   │
         ┌─────────┴─────────────────┐
         │ A.sh 调用:                │
         │ ./B.sh "hello" "$1" "$2"  │
         └─────────┬─────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │   B.sh 的参数空间    │  ← 完全独立的上下文
         │   $# = 3            │  ← 不是 A 的 3，是 B 接收的 3
         │   $1 = hello        │  ← 不是 param1
         │   $2 = param1       │  ← A 的 $1
         │   $3 = param2       │  ← A 的 $2
         └─────────────────────┘
```

---

## ⚠️ 常见误区

### 误区 1: 以为参数会自动继承
```bash
# ❌ 错误理解
# A.sh 接收: ./A.sh param1 param2
# A.sh 中: $# = 2

# A.sh 调用 B.sh
./B.sh  # 没传参数

# ❌ 错误认为 B.sh 中:
# $# = 2  # 错！实际是 0
# $1 = param1  # 错！实际是空
```

### 误区 2: 以为环境变量和参数一样
```bash
# ✅ 环境变量会继承（使用 export）
# A.sh
export VAR="value"
./B.sh

# B.sh 中
echo $VAR  # 输出: value ✅

# ❌ 但参数不会继承
# A.sh
$1 = "param1"
./B.sh

# B.sh 中
echo $1  # 输出: (空) ❌
```

### 误区 3: 以为 "$@" 和 $@ 一样
```bash
# 有空格的参数
# ./A.sh "hello world" "foo bar"

# A.sh 中:
$1 = "hello world"
$2 = "foo bar"

# ❌ 不加引号
./B.sh $@
# B.sh 收到 4 个参数: hello, world, foo, bar

# ✅ 正确加引号
./B.sh "$@"
# B.sh 收到 2 个参数: "hello world", "foo bar"
```

---

## 🛠️ 实战技巧

### 技巧 1: 参数验证在每个脚本中单独做
```bash
# A.sh
if [ $# -ne 2 ]; then
    echo "A.sh 需要 2 个参数"
    exit 1
fi

# B.sh 也要做自己的验证
if [ $# -ne 3 ]; then
    echo "B.sh 需要 3 个参数"
    exit 1
fi
```

### 技巧 2: 使用变量名传递更清晰
```bash
# A.sh
user_id="$1"
action="$2"
./B.sh "$user_id" "$action" "$(date +%Y%m%d)"

# B.sh
user="$1"
action="$2"
date="$3"
echo "处理用户 $user 的 $action 操作，日期 $date"
```

### 技巧 3: 记录参数用于调试
```bash
# B.sh
echo "B.sh 接收参数: $#" >> debug.log
echo "参数列表: $@" >> debug.log
```

---

## 📝 总结

### 核心答案

| 问题 | 答案 |
|------|------|
| B.sh 中的 `$#` 是什么？ | A 调用 B 时传递给 B 的参数个数 |
| B.sh 会自动获得 A.sh 的参数吗？ | ❌ 不会，必须显式传递 |
| 如何让 B.sh 获得 A.sh 的所有参数？ | 使用 `./B.sh "$@"` |
| 如何让 B.sh 获得 A.sh 的第 1 个参数？ | 使用 `./B.sh "$1"` |

### 记忆口诀

```
脚本参数不继承，
必须显式来传递，
$# 看的是自己，
不看父脚本参数。
```

### 最佳实践

1. ✅ **总是显式传递参数** - 不要假设参数会自动传递
2. ✅ **使用引号包裹 "$@"** - 保护带空格的参数
3. ✅ **每个脚本独立验证参数** - 不要依赖父脚本的验证
4. ✅ **使用有意义的变量名** - 提高代码可读性
5. ✅ **添加调试日志** - 记录收到的参数

---

## 🔍 实际项目示例

### download_custpro_cmd_nb.sh → custpro_nb.sh

```bash
# download_custpro_cmd_nb.sh (父脚本)
# 执行: ./download_custpro_cmd_nb.sh
# 或:   ./download_custpro_cmd_nb.sh rerun

if [ $# -eq 0 ]
then
   rerun=0
   rerun_file=""
else
   rerun=1
   rerun_file="${WRKD}/rerun_cust.log"
fi

today=$(date +%Y%m%d)
date_pattern=$(date +%Y%m%d%H%M%S)

# 调用子脚本，传递 4 个参数
${SRCAPPL}/custpro_nb.sh ${rerun} ${rerun_file} ${today} ${date_pattern} >> ${logf}
```

```bash
# custpro_nb.sh (子脚本)
# custpro_nb.sh 中的参数情况:

# 父脚本执行: ./download_custpro_cmd_nb.sh
# custpro_nb.sh 收到:
$# = 4
$1 = 0              # rerun
$2 = ""             # rerun_file (空)
$3 = 20260403       # today
$4 = 20260403142530 # date_pattern

# 父脚本执行: ./download_custpro_cmd_nb.sh rerun
# custpro_nb.sh 收到:
$# = 4  # 仍然是 4，不是 1！
$1 = 1              # rerun
$2 = /path/to/rerun_cust.log  # rerun_file
$3 = 20260403       # today
$4 = 20260403142530 # date_pattern
```

**关键点：** custpro_nb.sh 的 `$#` 永远是 4，与父脚本收到多少参数无关！

