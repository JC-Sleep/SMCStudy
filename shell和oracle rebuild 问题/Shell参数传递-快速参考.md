# Shell 参数传递 - 快速参考

## 🎯 一句话回答

**B.sh 中的 `$#` 是指 A 调用 B 时传递给 B 的参数个数，不是 A 接收的参数个数！**

---

## 📊 对比表格

### 场景：用户执行 `./A.sh param1 param2 param3`

| A.sh 的调用方式 | A.sh 中的 $# | B.sh 中的 $# | B.sh 中的 $1 | B.sh 中的 $2 |
|----------------|-------------|-------------|-------------|-------------|
| `./B.sh` | 3 | **0** | (空) | (空) |
| `./B.sh "$1"` | 3 | **1** | param1 | (空) |
| `./B.sh "$1" "$2"` | 3 | **2** | param1 | param2 |
| `./B.sh "$@"` | 3 | **3** | param1 | param2 |
| `./B.sh "hello" "world"` | 3 | **2** | hello | world |
| `./B.sh "x" "$1" "$2"` | 3 | **3** | x | param1 |

---

## 🔑 关键规则

### 规则 1：参数不会自动传递
```bash
# A.sh 接收 3 个参数
./A.sh p1 p2 p3

# A.sh 中调用 B.sh 不传参
./B.sh

# ❌ B.sh 中的 $# 不是 3
# ✅ B.sh 中的 $# 是 0
```

### 规则 2：每个脚本有独立的参数空间
```
用户 → A.sh (参数空间1) → B.sh (参数空间2)
                          ↑
                    必须显式传递
```

### 规则 3：透传所有参数使用 "$@"
```bash
# A.sh
./B.sh "$@"  # 将 A 的所有参数传给 B

# 现在 B.sh 的 $#、$1、$2... 与 A.sh 相同
```

---

## 💡 实际项目例子

### download_custpro_cmd_nb.sh (A) → custpro_nb.sh (B)

```bash
# 用户执行（无参数）
$ ./download_custpro_cmd_nb.sh

# A 脚本中：
$# = 0  # ← A 接收 0 个参数
rerun=0

# A 调用 B：
./custpro_nb.sh ${rerun} ${rerun_file} ${today} ${date_pattern}

# B 脚本中：
$# = 4  # ← B 接收 4 个参数（不是 0！）
$1 = 0
$2 = (rerun_file 的值)
$3 = (today 的值)
$4 = (date_pattern 的值)
```

```bash
# 用户执行（1 个参数）
$ ./download_custpro_cmd_nb.sh rerun

# A 脚本中：
$# = 1  # ← A 接收 1 个参数
rerun=1

# A 调用 B（仍然传递 4 个参数）：
./custpro_nb.sh ${rerun} ${rerun_file} ${today} ${date_pattern}

# B 脚本中：
$# = 4  # ← B 仍然接收 4 个参数（不是 1！）
$1 = 1
$2 = (rerun_file 的值)
$3 = (today 的值)
$4 = (date_pattern 的值)
```

---

## ✅ 记忆要点

1. **B.sh 的 `$#` = A 调用 B 时传递的参数个数**
2. **B.sh 看不到 A.sh 接收的参数（除非 A 显式传递）**
3. **参数不会自动继承，必须显式传递**
4. **使用 `"$@"` 可以透传所有参数**

---

## 🎓 测试方法

创建两个简单脚本测试：

```bash
# test_A.sh
echo "A 接收参数: $#"
./test_B.sh "fixed_param"

# test_B.sh
echo "B 接收参数: $#"
```

执行：
```bash
$ ./test_A.sh p1 p2 p3
A 接收参数: 3
B 接收参数: 1  # 不是 3！
```

