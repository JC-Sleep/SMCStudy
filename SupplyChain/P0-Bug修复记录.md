# P0 Bug 修复记录（2026-06-03）

> 本次修复 5 个 P0 Critical Bug + 顺手清理 1 个 P2 dead code。
> 详细危害分析见 `Bug不修复的危害与时序图.md`。
> 完整 Bug 清单见 `plan-supplyChain.prompt.md`。

## ✅ 修复清单

| Bug | 文件 | 修复方式 | 验证点 |
|-----|------|---------|--------|
| **B1** | `listener/InventoryListener.java` | 消费前查 `sc_inventory_log` 的 (ref_no, op_type) 幂等键，存在则直接 ack | Kafka 重投递不会重复扣 DB |
| **B12** | `listener/InventoryListener.java` | `affected==0` 改为 throw 让消息进 DLT | 不再静默吞掉数据漂移 |
| **B3** | `service/impl/FulfillmentServiceImpl.java` + `mapper/FulfillmentOrderMapper.java` | 新增 `cancelIfCancelable` 条件 update (`WHERE order_no=? AND status IN ('PENDING','PAID')`)；affected=0 直接退出不释放库存 | 并发取消只有 1 个线程释放 |
| **B4** | `service/impl/FulfillmentServiceImpl.java` | 状态白名单收紧到 PENDING/PAID（之前只排除 DELIVERED/CANCELLED） | OUTBOUND 后取消会抛 illegalStatus |
| **B5** | `service/impl/InventoryServiceImpl.java::inbound` | Redis 同步从 `SET DB值` 改为 `INCRBY qty`，且仅当 key 存在时才 INCRBY；不存在时跳过留给 warmup 处理 | 并发入库不再覆盖中间用户的扣减 |
| **B7** | `service/impl/InventoryServiceImpl.java::warmupRedisStock` | 从 `SET` 改为 `setIfAbsent` (SETNX)；多并发预热只有第 1 个生效 | 冷启动并发预热不再覆盖已扣减值 |
| **B19**(顺手) | `service/impl/InventoryServiceImpl.java` | 删除未使用的 `RedisTemplate<String,Object>` 字段 + import | dead code 清理 |

---

## 修复前后核心代码对比

### B1 — Kafka 消费者幂等

**前：**
```java
public void onDeduct(...) {
    int affected = inventoryMapper.lockQty(...);
    if (affected == 0) { log.warn(...); }   // ❌ 静默
    ack.acknowledge();
}
```

**后：**
```java
@Transactional
public void onDeduct(...) {
    if (logMapper.countByRefNoAndOpType(orderNo, "LOCK") > 0) {
        ack.acknowledge();
        return;   // ✅ 已处理过，幂等跳过
    }
    int affected = inventoryMapper.lockQty(...);
    if (affected == 0) {
        throw new IllegalStateException(...);   // ✅ 让消息进 DLT
    }
    writeLog(...);   // 写幂等流水
    ack.acknowledge();
}
```

幂等键：`sc_inventory_log.ref_no + op_type`（LOCK / UNLOCK / CONFIRM_MQ）。生产环境建议在 DDL 加唯一索引：

```sql
ALTER TABLE sc_inventory_log
  ADD UNIQUE KEY uk_ref_op (ref_no, op_type);
```

---

### B3 + B4 — cancelOrder 原子化 + 白名单

**前：**
```java
public void cancelOrder(String orderNo) {
    FulfillmentOrder order = requireOrder(orderNo);
    if (DELIVERED.equals(s) || CANCELLED.equals(s))   // ❌ OUTBOUND/PICKING 漏防
        throw illegalStatus(...);
    inventoryService.unlockStock(...);   // ❌ 并发都会执行
    order.setStatus(CANCELLED);
    orderMapper.updateById(order);
}
```

**后：**
```java
public void cancelOrder(String orderNo) {
    FulfillmentOrder order = requireOrder(orderNo);
    // B4：白名单
    if (!PENDING.equals(s) && !PAID.equals(s))
        throw illegalStatus("仅 PENDING/PAID 可取消");
    // B3：原子条件 UPDATE 抢占
    int affected = orderMapper.cancelIfCancelable(orderNo);
    if (affected == 0) return;   // 并发已被取消，跳过释放
    inventoryService.unlockStock(...);   // ✅ 只有抢到的线程才释放
}
```

---

### B5 — inbound Redis 累加

**前：**
```java
inventoryMapper.addQty(...);             // DB +qty
Inventory fresh = getInventory(...);     // 重读 DB
stringRedisTemplate.set(key, fresh.qty); // ❌ SET 覆盖
```

**后：**
```java
inventoryMapper.addQty(...);   // DB +qty
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey))) {
    stringRedisTemplate.opsForValue().increment(redisKey, qty);  // ✅ INCRBY 累加
}
// key 不存在则不创建，让首次 lockStock 的 -2 路径走 warmup（B7 已 SETNX 安全）
```

---

### B7 — warmup SETNX

**前：**
```java
stringRedisTemplate.opsForValue().set(key, dbQty);   // ❌ 100并发都覆盖
```

**后：**
```java
Boolean ok = stringRedisTemplate.opsForValue()
    .setIfAbsent(key, String.valueOf(dbQty));        // ✅ 只有第1个生效
```

---

## 验证

- ✅ `mvn compile` 通过，无编译错误
- ⏳ 建议补上压测验证：100 线程并发下单 / 取消 / 入库 + warmup，观察 Redis vs DB 漂移为 0

## 还未修的高危 Bug

| 优先级 | Bug | 描述 |
|--------|-----|------|
| 🟡 P1 | B6 | createOrder Redis已扣但订单insert失败的孤儿扣减（需 TransactionalEventListener AFTER_COMMIT 改造） |
| 🟡 P1 | B8 | allocateFifo 加 Redisson 锁（依赖 Redisson 启用，需先配 Redis 密码） |
| 🟡 P1 | B9 | reconcile 修复期加锁或仅记录不修复 |
| 🟡 P1 | B10/B11 | DLT 路由配置 + 失败消息持久化告警 |
| 🟡 P1 | B13 | 定时任务接 ShedLock |
| 🟢 P2 | B14~B21 | 代码质量类 |

