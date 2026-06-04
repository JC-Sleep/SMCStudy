# JMeter 跑 B7 复现

## 前提

1. 已安装 JMeter（推荐 5.6+），`jmeter.bat` 在 PATH 中
2. sc-app 容器正在运行（端口 8091）

## 跑法 A — GUI

```powershell
# 1. 重置库存到 10 + 清空 Redis（模拟冷启动）
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\scripts\repro
docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -e "DELETE FROM sc_fulfillment_order WHERE sku_id=1001; UPDATE sc_inventory SET available_qty=10, locked_qty=0, total_qty=10 WHERE sku_id=1001 AND warehouse_id=1; DELETE FROM sc_inventory_log WHERE sku_id=1001;"
docker exec sc-redis redis-cli DEL "sc:inventory:available:1:1001"

# 2. GUI 模式打开
jmeter -t .\jmeter\B7-100threads.jmx
# 在 GUI 中点绿色 ▶ 启动
```

## 跑法 B — 命令行（推荐用于自动化）

```powershell
# 同上重置数据 ↑

# 命令行运行，输出 CSV
jmeter -n -t .\jmeter\B7-100threads.jmx -l .\jmeter\result.jtl -e -o .\jmeter\html-report

# 看汇总：
# 修复前：100 个请求 99 个 200，1 个 200（实际超卖）
# 修复后：10 个 200，90 个 400 (stockInsufficient)

# 检查最终 DB 订单数
docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -N -e "SELECT COUNT(*) FROM sc_fulfillment_order WHERE sku_id=1001 AND status<>'CANCELLED'"
# 修复前 → 100（超卖90）
# 修复后 → 10（正确）

# 打开 HTML 报告
start .\jmeter\html-report\index.html
```

## 关键观察点

| 指标 | 修复前 | 修复后 |
|------|--------|--------|
| HTTP 200 数 | ≈100 | ≈10 |
| HTTP 400 (库存不足) | ≈0 | ≈90 |
| DB 实际订单数 | **超过 10** ❌ | **正好 10** ✅ |
| DB available_qty | 异常负数 / 0 | 0 |

