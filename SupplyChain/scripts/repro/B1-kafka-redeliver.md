# B1 复现指南：Kafka 重投递导致 DB 库存重复扣减

## 原理

Kafka 是 at-least-once 语义。当消费者 update DB 成功后、ack 提交前 JVM 崩溃，重启后消息会被**重新投递**，未做幂等就会重复扣 DB。

## 复现步骤

### 准备

```powershell
cd C:\WorkSoftware\a_program\selft\smartoneCloud\SupplyChain\scripts\repro
.\00-setup.ps1
```

### 关键技巧：暂停消费者让消息堆积

我们没法精确"刚 update 完就 kill"，但可以利用 **Kafka offset 回退手动模拟重复消费**：

#### 方法 A — 利用消费组 reset offset（最稳定）

```powershell
# 1. 下 5 笔订单（库存被扣 25），等消费完
1..5 | ForEach-Object {
    $body = @{ storeId=1; skuId=1001; qty=5; payAmount=49.5; address='测试'; warehouseId=1 } | ConvertTo-Json
    Invoke-RestMethod -Uri 'http://localhost:8091/api/fulfillment/order/create' -Method Post -Body $body -ContentType 'application/json' | Out-Null
}
Start-Sleep -Seconds 3
.\99-state.ps1
# 期望 DB locked_qty = 25
```

```powershell
# 2. 停止 sc-app 容器（释放消费组）
docker stop sc-app
```

```powershell
# 3. 把消费组 sc-inventory-deduct 的 offset 回退到最早
docker exec sc-kafka kafka-consumer-groups --bootstrap-server localhost:9092 `
  --group sc-inventory-deduct --topic sc.inventory.deduct `
  --reset-offsets --to-earliest --execute
```

```powershell
# 4. 启动 sc-app，会重新消费这 5 条消息
docker start sc-app
Start-Sleep -Seconds 8
.\99-state.ps1
```

### 预期结果对比

| 状态 | 修复前（buggy） | 修复后 |
|------|----------------|--------|
| DB locked_qty | **50**（被加了 2 次）❌ | **25**（幂等键拦截）✅ |
| `sc_inventory_log` LOCK 行数 | 5 | 5（重复消费时 countByRefNoAndOpType 返回 1，跳过）|
| 应用日志 | 静默重复 SQL | 出现 `[DEDUCT落库][幂等] 已处理过，跳过` |

### 验证幂等日志

```powershell
docker logs sc-app --tail 200 | Select-String "幂等"
```

修复后应看到 5 条 `[DEDUCT落库][幂等] 已处理过，跳过 orderNo=...`

#### 方法 B — DLT 验证（B12 配套）

故意让 Kafka 收到一条 DB 处理会失败的消息（例如把库存改为 0 后再发一条 deduct）：

```powershell
# 把 DB 库存清零，但保留 Redis 让 Lua 通过
docker exec sc-mysql mysql -uroot -proot123 sc_supply_chain -e `
  "UPDATE sc_inventory SET available_qty=0 WHERE sku_id=1001 AND warehouse_id=1"
docker exec sc-redis redis-cli SET "sc:inventory:available:1:1001" 100

# 下单（Lua 通过、Kafka 消息进入消费器）
$body = @{ storeId=1; skuId=1001; qty=5; payAmount=49.5; address='测试'; warehouseId=1 } | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:8091/api/fulfillment/order/create' -Method Post -Body $body -ContentType 'application/json'
Start-Sleep -Seconds 5

# 看日志：修复前 → log.warn 后 ack 静默吞掉
#         修复后 → throw IllegalStateException 触发重试，最终进 DLT
docker logs sc-app --tail 100 | Select-String "DEDUCT落库"
```

