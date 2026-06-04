# P0 Bug 复现脚本

> ⚠️ 前提：当前 Docker 容器跑的是 **修复前的代码**（你还没重新打包），可以直接复现。
> 复现完后再 `mvn package` + `docker-compose up -d --build` 重启 sc-app，重新跑这些脚本验证修复生效。

## 📋 目录结构

```
scripts/repro/
├── README.md              ← 本文件（操作指南）
├── 00-setup.ps1           ← 准备测试数据（SPU/SKU/库存=100）
├── 99-state.ps1           ← 实时打印 Redis + DB 状态（核心验证工具）
├── 99-cleanup.ps1         ← 清理测试数据（重置）
├── B1-kafka-redeliver.md  ← 手动步骤（需要 kill 容器）
├── B3-cancel-double.ps1   ← 50 并发取消同一订单
├── B4-cancel-after-outbound.ps1
├── B5-inbound-race.ps1    ← 并发入库 + 并发下单
├── B7-warmup-race.ps1     ← 冷启动 100 并发首单
└── jmeter/
    └── B7-100threads.jmx  ← JMeter 计划（更可控的高并发）
```

## 🚀 使用流程（修复前验证 Bug 真实存在）

```powershell
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\scripts\repro

# 1. 准备数据（SPU=1, SKU=1001, 仓库=1, 初始库存=100）
.\00-setup.ps1

# 2. 查看初始状态（应该 Redis=null, DB available=100）
.\99-state.ps1

# 3. 复现某个 Bug
.\B7-warmup-race.ps1      # 例：复现冷启动竞态超卖

# 4. 再次查看状态，对比异常值
.\99-state.ps1

# 5. 清理后复现下一个
.\99-cleanup.ps1
```

## 🧪 各 Bug 的预期复现结果

| 脚本 | 修复前应观察到 | 修复后应观察到 |
|------|--------------|--------------|
| **B1** | DB locked_qty 比下单总和大 N 倍 | locked_qty = 实际下单量 |
| **B3** | Redis 远大于初始值（凭空多货）| Redis = 初始值 - 0（没释放任何东西）|
| **B4** | OUTBOUND 状态成功调用 cancel 返回 200 | 抛 illegalStatus 400 |
| **B5** | Redis ≠ DB（Redis 偏大）| Redis = DB |
| **B7** | 100 并发抢 10 件库存 → 99 单成功 ❌ | 仅 10 单成功，其余库存不足 |

---

## 🔁 重新打包+重启（验证修复）

```powershell
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain
& "C:\WorkSoftware\Idea\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q
docker-compose up -d --build supply-chain-app
docker logs -f sc-app | Select-String "Started SupplyChainApplication"   # 等启动完
# 然后回到 scripts/repro 目录重跑各脚本，应看到不再超卖
```

## 💡 JMeter vs PowerShell 选择

- **PowerShell 脚本**：利用 .NET RunspacePool 实现毫秒级真并发，足够复现绝大多数 Bug，0 依赖
- **JMeter (`jmeter/B7-100threads.jmx`)**：用于需要更多线程（>200）+ 性能报告时；只对 B7 提供，因为其他 Bug 不需要那么高并发

