# 🚨 SupplyChain 系统 Bug 不修复的危害详解

> 本文档把 21 个 Bug（B1~B21）的"不修复后果"全部摊开讲清楚。每个关键 Bug 用**编号步骤+箭头**描述时序，让你直观看懂"什么时序触发、为什么出事、最终损失多大"。

---

## 📊 危害总览表

| Bug | 不修复时业务后果 | 触发概率 | 资损方向 | 排查难度 |
|-----|----------------|--------|---------|---------|
| **B1** | DB 锁定库存被加 N 倍 | 高（Kafka 重启/Rebalance必出） | 🔴 数据漂移→超卖 | ⭐⭐⭐⭐ |
| **B2** | 合法订单出库失败报错 | 中（极速 demo 复现） | 🟡 用户体验差 | ⭐⭐⭐ |
| **B3** | 一次取消释放多倍库存 | 中（用户狂点取消按钮） | 🔴 凭空多货→超卖 | ⭐⭐⭐⭐ |
| **B4** | 已出库订单还能取消 | 中 | 🔴 凭空多货+真实少货 | ⭐⭐⭐ |
| **B5** | 并发入库 Redis 被覆盖 | 中（多供应商同时入库） | 🔴 超卖 | ⭐⭐⭐⭐⭐ |
| **B6** | Redis 已扣但订单不存在 | 低但严重 | 🔴 永久库存丢失 | ⭐⭐⭐⭐⭐ |
| **B7** | 缓存预热竞态导致超卖 | 高（冷启动+并发首单） | 🔴 超卖 | ⭐⭐⭐⭐ |
| B8  | FIFO 批次扣减事务报错 | 中 | 🟡 业务报错 | ⭐⭐⭐ |
| B9  | 对账反而把 Redis 改错 | 低 | 🔴 超卖 | ⭐⭐⭐⭐ |
| B10 | 失败消息无限重试卡死队列 | 高 | 🟡 消费阻塞 | ⭐⭐ |
| B11 | 失败消息丢失无法追溯 | 必现 | 🟡 运维盲区 | ⭐ |
| B12 | DB 扣减失败被静默吞掉 | 高 | 🔴 数据漂移 | ⭐⭐⭐⭐ |
| B13 | 多副本部署任务重复执行 | 上 K8s 后必现 | 🔴 重复补货/对账错乱 | ⭐⭐ |
| B14 | 首次入库并发抛错回滚 | 低 | 🟡 业务报错 | ⭐⭐ |
| B15 | 缓存未热 → 误触发补货 | 中（Redis 重启后） | 🟡 资金浪费 | ⭐⭐⭐ |
| B16 | 订单号 UUID 截断冲突 | 极低 | 🟡 偶发下单失败 | ⭐⭐ |
| B17~B21 | 代码质量/运维隐患 | - | 🟢 长期债 | - |

---

# 🔴 Critical 危害详解（P0，必修）

## B1：Kafka 消费者无幂等 → DB 库存重复扣减

**故障时序：**

```
[1] FulfillmentService → Kafka 发送 deduct(orderNo=A, qty=5)
[2] Kafka → InventoryListener 投递消息 #100
[3] Listener → MySQL: UPDATE locked_qty += 5  (成功 locked=5)
[4] ⚠️ JVM 崩溃 / Pod 被kill，ack 还没提交
[5] Kafka offset 未推进，消息 #100 仍在队列
========== 容器重启后 ==========
[6] Kafka → Listener 重投递消息 #100
[7] Listener → MySQL: UPDATE locked_qty += 5  (再次成功 locked=10) ❌
[8] Listener → Kafka: ack
```

**最终结果**：用户只下单 5 件，DB `locked_qty=10`，**真实可用库存被多吞 5 件**。

**业务危害**：
- 后续 SQL `WHERE locked_qty >= qty` 仍能通过，库存被吞掉无人察觉
- 凌晨对账发现 Redis(=95) vs DB(available=90) 差 5
- 对账"以 DB 为准"反向把 Redis 改成 90 → **凭空蒸发 5 件可售库存** → 真实买家买不到 → 投诉

---

## B3：cancelOrder 无原子保护 → 双倍释放库存

**故障时序：**（用户狂点取消按钮，前端没防抖）

```
[1] 用户 → 第1次点击取消 → 线程T1
[2] 用户 → 第2次点击取消（10ms后） → 线程T2

[3] T1 → DB: SELECT order WHERE no=A → status=PAID ✅ 可取消
[4] T2 → DB: SELECT order WHERE no=A → status=PAID ✅ 可取消（T1还没改）

[5] T1 → Redis: INCRBY available 5 → 105 ❌
[6] T2 → Redis: INCRBY available 5 → 110 ❌❌（凭空多 10）
[7] T1 → DB: UPDATE locked_qty -= 5 (affected=1)
[8] T2 → DB: UPDATE locked_qty -= 5 (locked已0，affected=0 静默失败)
[9] T1 → DB: UPDATE order SET status=CANCELLED
[10] T2 → DB: UPDATE order SET status=CANCELLED (覆盖)
```

**最终结果**：原本可用 100，下单 5 后取消 1 次应回 100，实际 Redis=110。

**业务危害**：这 10 件"幻影库存"被后续买家成功下单 → 仓库无货 → 用户支付完发现"无货可发" → **赔付+口碑双重打击**。

---

## B4：cancelOrder 允许 OUTBOUND 后取消 → 库存彻底错乱

**故障时序：**

```
[1] 订单 A 已 outbound：locked_qty=0, total_qty=95（货已发出去5件）
[2] 客服 → cancelOrder(A) 状态=OUTBOUND
[3] Service: 仅检查 !DELIVERED && !CANCELLED → 通过 ✅
[4] Service → InventoryService.unlockStock(qty=5)
[5] Redis: INCRBY available 5 → 100  ❌ (凭空多 5)
[6] DB: UPDATE locked_qty -= 5 → locked已=0, affected=0 静默失败
[7] DB: UPDATE order SET status=CANCELLED ✅
```

**最终结果**：Redis 可售 100（虚假），DB total 95（真实，货已送出）→ **实际超卖 5 件，相当于白送**。

---

## B5：inbound Redis 同步非原子 → 并发入库覆盖

**故障时序：**（两个采购员同时入库 + 期间有人下单）

```
[初始] Redis available=0, DB available_qty=0

[1] 采购员P1 → DB: UPDATE available_qty += 100 → DB=100
[2] P1     → DB: SELECT 读回 100
[3] P1     → Redis: SET available = 100   (Redis=100 ✅)

[4] 采购员P2 → DB: UPDATE available_qty += 50 → DB=150
[5] P2     → DB: SELECT ... 还在执行

[6] 买家U  → Redis: Lua DECRBY 10 → 90 (合法下单)
[7] P2     → DB SELECT 返回 150
[8] P2     → Redis: SET available = 150  ❌ (覆盖了U的扣减)

[最终] Redis=150, 真实应=140 → 凭空多 10 件
```

**业务危害**：幻影库存导致**真实超卖**。隐蔽性极强：DB 是对的、Redis 是错的，对账反向修复时**已超卖订单已发出**，无法挽回。排查难度⭐⭐⭐⭐⭐。

---

## B6：lockStock 后 createOrder 失败 → Redis 已扣但订单不存在

**故障时序：**

```
[1] 用户 → createOrder(qty=5)  [@Transactional 开启]
[2] Service → InventoryService.lockStock(5)
[3]   → Redis: Lua DECRBY → 95 ✅
[4]   → Kafka: send(deduct, qty=5) ✅ (消息已飞出)
[5] Service → DB: INSERT order
[6] DB: ❌ DuplicateKeyException (orderNo 撞库 / 死锁)
[7] @Transactional 回滚 → 但 Redis 不会回滚，Kafka 消息也不会撤回

[8] Kafka → Listener 消费 deduct 消息
[9] Listener → DB: UPDATE locked_qty += 5  (订单根本不存在却扣了库存)
```

**最终结果**：Redis available=95（少 5），DB locked_qty=5（凭空锁 5），订单表**没有这条订单**。这 5 件库存**永久消失**。

**业务危害**：用户没收到下单成功不会主动取消，对账也不会自动恢复（DB 状态看起来"正常被锁定"）。需要人工 join Kafka 历史+订单表才能找出"孤儿扣减"。

---

## B7：warmupRedisStock 无锁 → 缓存预热竞态超卖

**故障时序：**（应用刚启动 / Redis 重启，100 个并发首单）

```
[初始] Redis key 不存在（冷启动）, DB available=100

[1] 用户U1~U99 → Lua lock(1) → 全返回 -2 (key不存在)
[2] U1~U99 同时 → warmupRedisStock() → 全部 SELECT DB → 拿到 100
[3] U100 → Lua lock(1) → 也返回 -2
[4] U100 → warmupRedisStock() → SELECT DB → 拿到 100

[5] U1 → Redis: SET available = 100      (Redis=100)
[6] U1 → 重试 Lua lock(1) → 99 ✅         (Redis=99)
[7] U100 → Redis: SET available = 100  ❌ (把 U1 的扣减覆盖回 100)
[8] U100 → 重试 Lua lock(1) → 99 ✅       (Redis=99，实际应=98)
... 99 个用户同样
```

**最终结果**：100 个并发都成功扣减，Redis 因被反复 SET 覆盖，**最终值 >> 真实剩余**。

**业务危害**：冷启动+秒杀场景必然超卖。"10 件库存抢 100 单"，99 单都成功 → 仓库爆单 → 公司赔付。

---

# 🟡 High 危害详解（P1）

## B8：FIFO 并发分配冲突

```
[1] T1 → SELECT 批次列表 ORDER BY inbound_time → [B1:remain=10, B2:remain=5]
[2] T2 → SELECT 批次列表 → [B1:remain=10, B2:remain=5] (脏读)
[3] T1 → UPDATE B1 SET remain-=8 WHERE remain>=8 → ✅
[4] T2 → UPDATE B1 SET remain-=8 WHERE remain>=8 → ❌ (remain=2 < 8, affected=0)
[5] T2 → throw "FIFO批次扣减失败" → 事务回滚，业务报错
```

**危害**：T2 用户下单已成功（Redis 已扣）+ 支付已成功 → 出库报错。**Redis 与 DB 永久不一致**直到对账。

---

## B9：reconcile 修复时无锁 → 反向破坏

```
[凌晨2点] ReconcileJob 启动
[1] Job → Redis GET → 98
[2] Job → DB SELECT → 100 (因还有未消费 deduct 消息)
[3] Job 判定 diff=2，准备修复
[4] 凌晨用户 → Redis Lua DECRBY 1 → 97 ✅
[5] Job → Redis SET available = 100 ❌ (把用户的扣减覆盖)
[最终] Redis=100，真实应=97，凭空多 3
```

**危害**：**对账任务本身造成超卖**，最讽刺的 bug。

---

## B10/B11：DLT 不工作 + 失败消息只打日志

```
[1] Kafka → Listener 投递（消息内容损坏）
[2] Listener → throw NPE
[3] 默认 SeekToCurrentErrorHandler → 无限重试同一条
[4] Kafka → 重投递 → throw NPE → 重投递 → throw NPE → ... (死循环)
[5] 整条 topic 消费组卡死 → 后续所有消息全部阻塞
[6] 日志文件刷海量错误 → 无人能筛出失败列表
```

**危害**：单条毒消息阻塞**整条 topic 的消费**，所有用户库存无法落库 → 雪崩。

---

## B12：affected==0 静默吞掉

```
[1] Kafka → Listener: deduct(orderNo=A, qty=5)
[2] Listener → DB: UPDATE locked WHERE available>=5 → affected=0 (库存不够)
[3] Listener: log.warn(...) + ack ❌
[4] 消息正常确认，永久丢失
[5] Redis 已扣 ≠ DB 未扣 → 永久数据漂移
```

**危害**：和 B6 一样形成**孤儿扣减**，永远找不回的库存。

---

## B13：多副本定时任务重复

```
[凌晨2点 K8s 三副本同时触发]
[1] Pod1 → DB 对账 SKU=X，diff=2，修复 Redis=100
[2] Pod2 → DB 对账 SKU=X，此时 Redis=100，diff=0，跳过
[3] Pod3 → DB 对账 SKU=X，期间用户下单 Redis=99，diff=-1，再次修复 ❌
[最终] 三副本互相覆盖，最终值不可预测
```

**危害**：上 K8s 后必现，对账漂移、补货单 3 倍下单（采购方收到 3 倍订单）、预警短信 3 倍发送。

---

## B15：Redis 未命中误判 0 → 误补货

```
[1] Redis 重启后所有 key 全空
[2] ReplenishmentJob → checkAndTrigger(skuX, minQty=10)
[3] Service → Redis GET available → null
[4] Service: 视为 0 < 10 ⚠️
[5] Service → 触发补货单 qty=500 → 通知供应商 ❌
[6] 供应商收到误下单 → 仓库爆仓 / 资金占用
```

**危害**：每次 Redis 故障重启，自动触发**全仓所有 SKU 补货** → 资金灾难。

---

# 🟢 Medium 危害（P2）

| Bug | 危害 |
|-----|-----|
| B14 | 首次入库并发抛 DuplicateKey，事务回滚，采购员看到报错以为系统坏了 |
| B16 | UUID 截断 16 位，百万级订单可能撞 → 下单偶发 500 |
| B17 | 上下架并发流水写两条；未来加业务校验时被绕过 |
| B18 | freshType 接受任意字符串，DB 脏数据，效期任务漏扫 |
| B19 | dead code 误导后续开发者改错地方 |
| B20 | lockQty SQL 与 Redis 已扣冲突，affected=0 静默丢数据（关联 B12） |
| B21 | 手动 ack 配置缺失则 Acknowledgment 调用无效，offset 自动提交，重启可能跳过未处理消息 |

---

# 🎯 总结：一个都不修最坏会怎样？

```
系统上线
  → 有并发流量？
      ├─ 否 → demo顺利，看不到任何问题
      └─ 是 → Bug 开始触发
              ├─ B1 Kafka重启重复消费 ┐
              ├─ B5/B7 入库与预热竞态 ├─→ DB库存重复+少减
              ├─ B3/B4 取消异常       │
              └─ B6 孤儿扣减          ┘
                                       ↓
                          Redis vs DB 漂移
                                       ↓
                          凌晨2点对账任务
                                       ├─ B9 对账无锁 → 反向破坏
                                       └─ B13 多副本 → 副本互相覆盖
                                                     ↓
                                          🔴 真实超卖
                                                     ↓
                                          💸 用户投诉 + 赔付
                                                     ↓
                                          💀 口碑崩塌
```

**结论**：
1. **P0 五个 Bug（B1/B3/B4/B5/B7）** 会**直接导致超卖+资损**，必须在压测前修。
2. **P1 八个 Bug** 在多副本部署或异常运维时触发，上生产前必修。
3. **P2 八个 Bug** 是代码质量债，不影响演示但影响长期维护。

> **修复顺序**：B1 → B7 → B5 → B3 → B4 → B12 → B10/B11 → B6 → B13 → B9 → B8 → 其他

---

## 📎 关联文档

- 完整 Bug 清单：`plan-supplyChain.prompt.md` "🐛 当前系统已知 Bug & 设计缺陷" 章节
- P0 修复实施记录：`P0-Bug修复记录.md`

