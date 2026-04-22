# 秒杀系统 Kafka 消息可靠性完整指南

> 2026-04-22 | 从本系统真实代码出发，搞清楚：
> **消息积压 / 消费延迟 / 死信队列 / 重试队列**
> 每种场景什么时候发生、不做会怎样、怎么解决

---

## 一、先看本系统 Kafka 的完整消息流

```
                        ┌─────────────────────────────────────────────────────┐
                        │                 秒杀系统 Kafka 消息全景               │
                        └─────────────────────────────────────────────────────┘

用户抢购
  │
  ▼
[Redis Lua 原子扣库存] ← 成功
  │
  ▼
[创建 SeckillOrder，status=0（待处理）]
  │
  ▼
[发消息到 Kafka]
  │
  ├─→ Topic: coupon.seckill.order（10分区，并发核心）
  │     消费者：SeckillOrderKafkaConsumer
  │     做：扣积分 → 发券 → 扣DB库存 → 改订单status=1
  │     失败：回滚Redis库存+积分 → 改订单status=2
  │
  ├─→ Topic: coupon.grant（5分区）
  │     消费者：CouponGrantConsumer
  │     做：月度套餐赠券批量发放
  │
  ├─→ Topic: coupon.consumption.event（5分区）
  │     消费者：ConsumptionEventListener
  │     做：消费达标检测 → 发激励券
  │
  └─→ Topic: coupon.expire（3分区）
        消费者：过期处理
        做：标记券过期
```

---

## 二、消息积压（Backlog）

### 2.1 什么是积压？

```
生产速度 > 消费速度 → 消息在Topic里堆积 → 积压

例如：
  秒杀开始，1万用户同时抢购
  Producer：每秒发 10000 条消息到 Kafka
  Consumer（10线程）：每秒只能处理 2000 条（扣积分+发券+写DB，每条约5ms）

  1秒后积压：10000 - 2000 = 8000 条
  5秒后积压：40000 条
  1分钟后积压：480000 条 ← 用户8分钟后才能看到"抢购成功"！
```

### 2.2 积压怎么发生的（时序图）

```
秒杀开始瞬间（t=0）

用户1~10000             Redis          Kafka Topic(10分区)    消费者(10线程)        DB
    │                    │                    │                    │                │
    ├──→ Lua扣库存 ──────→│                    │                    │                │
    ├──→ ... (1万次)      │                    │                    │                │
    └── 全部成功，库存=0   │                    │                    │                │
                         │                    │                    │                │
    1万用户 ── 发1万条消息 ──────────────────→  │                    │                │
                         │                    │ 堆积1万条           │                │
                         │                    │ 消费者每秒消化2000条 │                │
                         │                    │ 剩8000条...         │── 扣积分 ──→  │
                         │                    │ 剩6000条...         │── 发券 ────→  │
                         │                    │ 剩4000条...         │── 写DB ────→  │
                         │                    │ 剩0条（约5秒后清空）  │                │
                         │                    │                    │                │
    用户5秒后查询订单 ─────────────────────────────────────────────── status=1(成功) │

[结果：高峰期用户要等5秒~几分钟才看到"抢购成功"]
```

### 2.3 积压不处理会怎样？

```
问题1：用户等待时间无限延长
  "抢购成功，处理中" → 10分钟后还没发券 → 用户投诉暴增

问题2：DB连接池耗尽
  20个消费者线程全力工作 → 每秒2000次DB写操作
  DB连接池20个全满 → 其他业务（查券、核销）全部超时

问题3：Kafka磁盘打满（最严重！）
  1200万条消息 × 1KB = 12GB
  磁盘满 → Kafka写入失败 → 新消息丢失 → 用户抢到但永远不发券！

问题4：Consumer重启后重复消费
  若offset未正确提交，重启后重复处理同一批订单 → 重复发券
```

### 2.4 本系统如何应对积压（已实现）

```yaml
# application.yml - 已有配置
kafka:
  consumer:
    max-poll-records: 500    # 每次批量拉取500条（减少网络往返）
    fetch-min-size: 1024     # 最小拉取1KB，凑够再拉
    fetch-max-wait: 500      # 最多等500ms
  listener:
    concurrency: 20          # 20个消费者线程（从原来10个升到20）
```

```
KafkaLagMonitor（每30秒检查）：
  积压 > 1万   → 普通告警
  积压 > 10万  → 严重告警 → 触发 KEDA 自动扩容

KEDA自动扩容流程：
  积压10万 → 3个Pod → 自动扩到6个Pod
  消费线程：20×3=60 → 20×6=120（翻倍）
  积压清空 → 自动缩回3个Pod
```

---

## 三、消费延迟（Lag）

### 3.1 两种延迟的区别

```
延迟类型1：消息处理本身慢（扣积分API超时）
  表现：一条消息处理耗时 5ms → 3000ms
  影响：这个线程3秒内只处理了1条，正常能处理600条

延迟类型2：积压导致的排队等待
  表现：消息发出去了，但前面还有10万条在排队
  影响：我的消息要等前面的全部处理完才轮到
```

### 3.2 消费延迟时序图（积分服务超时场景）

```
Consumer线程1              积分服务API            DB
    │                          │                  │
    │── 扣积分请求 ────────→   │                  │
    │                          │ (响应中...)       │
    │   等待...                 │                  │
    │   等待...（3秒超时）       │                  │
    │                          │← 超时！           │
    │  catch Exception          │                  │
    │  → 回滚Redis库存           │                  │
    │  → 标记订单失败 ───────────────────────────→  │
    │  → ack()                  │                  │

问题：这条消息耗时3秒（正常5ms），这个线程3秒只处理了1条
      20个线程如果都超时 → 消费速度接近0 → 积压爆发
```

### 3.3 ⚠️ 本系统当前问题：无超时保护

```java
// SeckillOrderKafkaConsumer.java 第70行
boolean deducted = pointsService.deductPoints(
        order.getUserId(),
        order.getPointsChannel(),
        order.getPointsUsed(),
        "秒杀抢券-" + order.getOrderNo()
);
// ← 如果积分服务宕机，这里阻塞多久？
//   取决于 HTTP 连接超时配置，可能是 30 秒！
//   20个线程全部卡在这里 → 30秒后才释放 → 积压30秒×1万条/秒 = 30万条积压
```

---

## 四、死信队列（Dead Letter Topic，DLT）

### 4.1 什么是死信队列？

```
正常：消息 → Consumer → 处理成功 → ack → 完成
失败（无DLT）：消息 → Consumer → 处理失败 → ack → 消息永远消失！
失败（有DLT）：消息 → Consumer → 处理失败 → 发到死信Topic → ack
                                                  ↓
                                           死信消费者持久化+告警
                                           等待人工/自动处理
```

### 4.2 死信队列时序图

```
【❌ 没有死信队列（本系统当前状态）】

Producer     正常Topic       Consumer           DB
    │              │               │              │
    │── 订单消息 ─→│               │              │
    │              │── 分发 ──→    │              │
    │              │               │─ 扣积分失败   │
    │              │               │  catch()      │
    │              │               │─ 回滚 ──────→ │
    │              │               │─ 改status=2 → │
    │              │               │─ ack() ←      │
    │              │ offset+1      │              │

[消息消失，订单失败，无任何后续处理，运营不知道，用户投诉]

═══════════════════════════════════════════════════════

【✅ 有死信队列的正确流程】

Producer  正常Topic   Consumer   死信Topic(DLT)   死信消费者       DB
    │          │           │            │               │           │
    │─ 消息 ─→ │           │            │               │           │
    │          │─ 分发 ──→ │            │               │           │
    │          │           │─ 失败      │               │           │
    │          │           │─ 发死信 ────────────────→  │           │
    │          │           │─ ack()     │               │           │
    │          │           │            │─ 监听消息 ─→  │           │
    │          │           │            │               │─ INSERT    │
    │          │           │            │               │  T_DEAD_   │
    │          │           │            │               │  LETTER ──→│
    │          │           │            │               │─ 发告警    │
    │          │           │            │               │  (钉钉)    │

[失败消息被保留，运营可查，可以人工补偿或批量重处理]
```

### 4.3 ❗ 本系统死信队列现状

```java
// SeckillOrderKafkaConsumer.java 第117~131行（当前代码）

} catch (Exception e) {
    log.error("秒杀订单处理失败: orderId={}", orderId, e);
    
    if (order != null) {
        rollbackRedisStock(order.getActivityId());   // ✅ 回滚Redis
        rollbackPointsIfNeeded(order);               // ✅ 回滚积分
        handleOrderFail(order, e.getMessage());      // ✅ 标记失败
    }
    
    ack.acknowledge();   // ← ❌ 直接消费掉，没有发死信！
                         //    失败消息永远消失，无法追查！
}
```

---

## 五、重试队列（Retry Topic）

### 5.1 什么是重试队列，什么时候需要？

```
场景：处理失败是因为"临时故障"
  DB抖动1秒（网络波动）
  积分服务短暂超时（重启中）
  
  这种情况：等1分钟再试，大概率成功！
  如果直接标记失败 → 永久损失用户权益

重试队列的逻辑：
  失败（第1次）→ 发到重试Topic → 1分钟后重试
  失败（第2次）→ 5分钟后重试
  失败（第3次）→ 30分钟后重试
  失败（第4次）→ 进死信队列 → 人工处理
```

### 5.2 重试时序图

```
【指数退避重试完整流程（积分服务临时故障）】

t=0    Consumer─────── 扣积分 ──────────────────→ 积分服务（超时！）
       Consumer─────── 发重试消息 ─→ 重试Topic {orderId, retryCount=1, nextRetry=+1min}
       Consumer─────── ack()

t=1min 重试Consumer←── 取出消息 ── 重试Topic
       重试Consumer─── 扣积分 ──────────────────→ 积分服务（已恢复 ✅）
       重试Consumer─── 发券 ✅
       重试Consumer─── 改status=1 ✅
       重试Consumer─── ack()

[用户延迟1分钟收到券，但没有损失权益！]

═══════════════════════════════════════════════════════

【如果积分服务持续故障，3次重试都失败】

t=0    失败 → 重试Topic retryCount=1，等1分钟
t=1min 失败 → 重试Topic retryCount=2，等5分钟
t=6min 失败 → 重试Topic retryCount=3，等30分钟
t=36min失败 → retryCount=4 ≥ MAX_RETRY(3)
              ├─→ 标记订单失败（status=2）
              └─→ 发死信Topic → 死信消费者 → 持久化 + 告警
                                               ↓
                                         运营人员收到告警
                                         查DB确认情况
                                         手动补发券给用户
```

---

## 六、幂等消费（防重复处理）

### 6.1 为什么需要幂等？

```
场景：消费者处理成功，但崩溃发生在 ack 之前

t=0   Consumer处理订单A → 发券成功 → DB已改status=1
t=1   Consumer准备 ack.acknowledge()
t=1   Consumer进程崩溃！ack 没有发出！
t=2   Kafka：没收到ack → 消息未被确认 → 重新投递
t=3   Consumer重启，再次收到订单A的消息
t=4   再次扣积分！再次发券！用户收到2张券！
```

### 6.2 幂等时序图

```
【✅ 本系统已实现幂等（SeckillOrderKafkaConsumer.java 第62行）】

Consumer重启后收到重复消息

Consumer                          DB
    │                              │
    │── SELECT * WHERE id=orderId ─→│
    │←── status=1（已成功处理过）── │
    │                              │
    │  if (order.getStatus() != 0) │
    │    ack()  // 直接跳过，不处理  │
    │    return                     │

[正确！订单只处理一次，用户只收到1张券] ✅
```

---

## 七、offset 提交策略对比

### 7.1 时序图对比

```
【❌ 自动提交（可能丢消息）】

t=0   Consumer 拉取 offset=100~199 共100条消息
t=3s  处理到 offset=150，Kafka自动提交 offset=150
t=4s  Consumer崩溃！
t=5s  重启后从 offset=151 继续
      → offset=151~199 的消息处理了
      → offset=100~150 中部分消息可能没处理完就提交了 → 消息丢失！

【✅ 手动提交（本系统使用）】

t=0   Consumer 拉取消息
t=1   处理消息：扣积分 → 发券 → 写DB
t=2   ack.acknowledge() → Kafka 提交 offset ✅
t=3   Consumer崩溃
t=4   重启后从下一条消息继续，之前处理完的不会重复

配置：ack-mode: manual ← 已在 application.yml 中配置 ✅
```

---

## 八、本系统现状 vs 应该有什么（对比表）

| 功能 | 本系统状态 | 问题后果 | 优先级 |
|------|---------|---------|--------|
| 消息积压监控 | ✅ KafkaLagMonitor（30秒检查） | — | — |
| 积压告警（1万/10万） | ✅ 已实现 | — | — |
| KEDA 自动扩容 | ✅ keda-kafka-scaler.yaml | — | — |
| 手动 ack（防丢消息） | ✅ ack-mode: manual | — | — |
| 幂等消费（status!=0跳过） | ✅ 第62行判断 | — | — |
| 消费失败回滚 Redis 库存 | ✅ rollbackRedisStock | — | — |
| 消费失败回滚积分 | ✅ rollbackPointsIfNeeded | — | — |
| **死信队列（DLT）** | ❌ **没有** | 失败消息消失，无法追查和补偿 | 🔴 高 |
| **重试队列（Retry Topic）** | ❌ **没有** | 临时故障直接标记失败，损失用户权益 | 🔴 高 |
| 消费超时保护 | ⚠️ 依赖HTTP超时 | 线程全部阻塞，积压爆发 | 🟡 中 |
| 批量消费优化 | ✅ max-poll-records: 500 | — | — |
| 并发消费 | ✅ concurrency: 20 | — | — |

---

## 九、补充实现代码（修复死信+重试）

### Step1：KafkaConfig 新增 Topic

```java
// KafkaConfig.java 新增
public static final String TOPIC_SECKILL_ORDER_RETRY = "coupon.seckill.order.retry";
public static final String TOPIC_SECKILL_ORDER_DLT   = "coupon.seckill.order.dlt";

@Bean
public NewTopic seckillOrderRetryTopic() {
    return TopicBuilder.name(TOPIC_SECKILL_ORDER_RETRY)
            .partitions(5).replicas(2).build();
}

@Bean
public NewTopic seckillOrderDltTopic() {
    return TopicBuilder.name(TOPIC_SECKILL_ORDER_DLT)
            .partitions(3).replicas(2).build();
}
```

### Step2：改造消费者 catch 块

```java
// SeckillOrderKafkaConsumer.java - catch 块改造

private static final int MAX_RETRY = 3;
private final KafkaTemplate<String, String> kafkaTemplate;

} catch (Exception e) {
    log.error("秒杀订单处理失败: orderId={}", orderId, e);

    if (order != null) {
        rollbackRedisStock(order.getActivityId());
        rollbackPointsIfNeeded(order);

        // ← 改造：不直接标记失败，先走重试
        int retryCount = getRetryCountFromHeader(record);

        if (retryCount < MAX_RETRY) {
            // 发重试队列（不改订单状态，等重试成功再改）
            sendToRetryTopic(order.getId(), retryCount + 1);
            log.warn("[重试] orderId={} 第{}次重试排队中", orderId, retryCount + 1);
        } else {
            // 超过重试次数 → 标记失败 + 发死信
            handleOrderFail(order, "重试" + MAX_RETRY + "次仍失败: " + e.getMessage());
            sendToDltTopic(order.getId(), e.getMessage());
        }
    }
    ack.acknowledge();
}

private int getRetryCountFromHeader(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader("X-Retry-Count");
    return header == null ? 0 : Integer.parseInt(new String(header.value()));
}

private void sendToRetryTopic(Long orderId, int retryCount) {
    // 指数退避延迟：1min, 5min, 30min
    int[] delays = {1, 5, 30};
    int delayMin = delays[Math.min(retryCount - 1, delays.length - 1)];
    log.warn("[重试队列] orderId={} retryCount={} 将在{}分钟后重试", orderId, retryCount, delayMin);

    kafkaTemplate.send(
        org.springframework.kafka.support.KafkaHeaders.TOPIC,
        KafkaConfig.TOPIC_SECKILL_ORDER_RETRY,
        orderId.toString()
    );
    // 注：Kafka不原生支持延迟消息；实际延迟可用Redis DelayQueue或DB定时扫描实现
    // 参考：GrantTaskRetryJob的指数退避方式（已实现）
}

private void sendToDltTopic(Long orderId, String reason) {
    log.error("[死信队列🚨] orderId={} reason={}", orderId, reason);
    kafkaTemplate.send(KafkaConfig.TOPIC_SECKILL_ORDER_DLT, orderId.toString());
    // TODO: alertService.sendCritical("秒杀订单死信！orderId=" + orderId);
}
```

### Step3：死信消费者

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderDltConsumer {

    private final SeckillOrderMapper orderMapper;

    @KafkaListener(topics = KafkaConfig.TOPIC_SECKILL_ORDER_DLT,
                   groupId = "seckill-dlt-consumer")
    public void handleDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Long orderId = Long.parseLong(record.value());
        log.error("[死信消费者🚨] orderId={} partition={} offset={}",
                orderId, record.partition(), record.offset());

        SeckillOrder order = orderMapper.selectById(orderId);
        if (order != null && order.getStatus() == 0) {
            // 确保订单标记为失败
            order.setStatus(2);
            order.setFailReason("消费重试超限，进入死信队列，需人工处理");
            orderMapper.updateById(order);
        }

        // 记录完整日志供运营查询
        log.error("[死信订单详情] orderId={} userId={} activityId={} pointsUsed={}",
                orderId,
                order != null ? order.getUserId() : "unknown",
                order != null ? order.getActivityId() : "unknown",
                order != null ? order.getPointsUsed() : 0);

        // TODO: 发告警（钉钉/Slack）给运营人员补偿
        ack.acknowledge();
    }
}
```

---

## 十、一句话记忆法

```
积压（Backlog）：
  → "生产比消费快，消息在排队等处理"
  → 解决：监控 + 自动扩容（本系统已有）

消费延迟（Lag）：
  → "处理每条消息太慢，线程都在等外部服务"
  → 解决：设置超时保护，不让线程无限等待

死信队列（DLT）：
  → "失败的消息放这里，不能就这么消失"
  → 解决：catch块发死信Topic，死信消费者持久化+告警

重试队列（Retry）：
  → "临时故障别急着认输，等一会儿再试"
  → 解决：指数退避重试，多次失败后才进死信

幂等消费：
  → "同一条消息被处理两次，结果要和处理一次一样"
  → 解决：消费前查订单status，已处理过就跳过（本系统已有）

手动ack：
  → "处理完再告诉Kafka'我收到了'，不能提前说"
  → 解决：ack-mode: manual（本系统已有）
```

---

*文档版本：v1.0 | 生成于 2026-04-22*
*关联文件：SeckillOrderKafkaConsumer.java / KafkaConfig.java / KafkaLagMonitor.java*
*待补充：死信队列（DLT）+ 重试队列（Retry Topic）实现*

