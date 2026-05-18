# Plan: 路由中心 + 流程引擎 — 电讯行业适用性分析与实施方案

## 背景

当前系统已实现 SCB/Alipay/CCB/CyberSource 多渠道策略模式路由、退款审批流、对账任务核心骨架。
本 Plan 聚焦两个核心问题：
1. **②路由中心**（健康检查+熔断）在电讯行业是否必要、价值在哪里
2. **③流程引擎**（Spring StateMachine）解决什么问题、是否值得引入

---

## 六大模块整体评估（电讯行业）

| 模块 | 已有基础 | 电讯是否必要 | 结论 |
|---|---|---|---|
| ①统一网关 | `PaymentGatewayRouter` + JWT Auth | ✅ 必要 | 已覆盖约70%，补剩余 |
| ②路由中心 | 基础渠道映射 | ✅ 关键改进点 | 重点新增健康检查+熔断 |
| ③流程引擎 | enum状态流转 | ✅ 合规需要 | Spring StateMachine 升级 |
| ④对账清算 | 任务框架已设计 | ✅ 财务强依赖 | 月结计费必须完善 |
| ⑤退款中心 | `RefundApprovalService` 已完整 | ✅ 已完成 | 无需额外投入 |
| ⑥风控模块 | 无 | ❌ 电讯不需要 | CyberSource Decision Manager已覆盖 |

---

## ② 路由中心分析

### 先理解"多渠道"不是"一个银行的多条路"

四个渠道服务**完全不同的客户群体**，不是同一家银行的备份路径：

| 渠道 | 服务的客户 | 电讯场景 |
|---|---|---|
| **CyberSource** | 持 Visa/MC/Amex 信用卡的用户 | 主力渠道，国际客户、企业客户缴费 |
| **SCB（渣打）** | 渣打银行合作专属页面 | 特定套餐/页面强制走渣打 |
| **Alipay** | 使用支付宝的内地用户/港漫游用户 | 大湾区用户缴费、预付充值 |
| **CCB（建行）** | 建行卡用户 | 内地来港用户、企业客缴费 |

> 路由的真实意义：持 Visa 的用户不可能走 Alipay，路由是根据用户支付方式自动匹配唯一能处理的渠道，**不是在多条路中选一条**。

### 为什么电商需要成本路由，电讯不需要

#### 电商的渠道结构（手续费差异巨大，可互换）

电商平台同时接入多个同质化渠道，同一笔订单可以用任意渠道收款：

| 渠道 | 手续费率 | 备注 |
|---|---|---|
| 微信支付 | 0.6% | 国内标准费率 |
| 支付宝 | 0.6% | 国内标准费率 |
| 银联云闪付 | 0.38% | 政策扶持，最便宜 |
| Visa（收单行A） | 1.5% + 固定费 | 国际卡贵 |
| Visa（收单行B） | 1.2% | 谈判结果更好 |
| PayPal | 3.4% + HKD2.35 | 最贵 |

> 日均交易100万元，从 PayPal 切到银联云闪付 → **每天省3万元，一年省1000万**。所以电商有专门团队做"费率路由优化"。

#### 电讯为什么没有这个问题

**核心原因：渠道由客户的支付方式决定，根本无法互相替代。**

```
电商："这笔订单用微信还是支付宝收？两个都行，选便宜的"
         ↑ 同质化渠道，可以任意切换

电讯："这个客户用 Visa 信用卡付款" → 只能走 CyberSource
     "这个客户用支付宝"           → 只能走 Alipay
     "这个客户走渣打页面"          → 只能走 SCB
         ↑ 渠道由客户决定，不存在"选哪个便宜"
```

#### 另一个深层原因：电讯手续费通过合同锁定

电讯公司对接 CyberSource 走企业商务合同：
- 费率在签合同时已谈定，**固定费率**，不随市场波动
- 通常有**年交易量承诺（commit volume）**，换渠道反而违约
- 关系型合作，有专属客户经理，不是随时可以"切走就切走"

电商平台（尤其中小商户）用自助注册费率，无锁定，随时可换，才有动力做成本路由。

| | 电商 | 电讯 |
|---|---|---|
| 渠道关系 | 同质化，可互换 | 异质化，不可换 |
| 费率结构 | 自助费率，差异大 | 合同锁定，差异小 |
| 路由价值 | 成本优化（省钱） | 稳定性保障（月结日不崩） |

---

### 健康检查/熔断在电讯行业的价值

**电商路由价值** = "A渠道贵，切B渠道省钱"（成本路由）— **电讯不需要**

**电讯路由价值** = **"月初账单日，CyberSource突然503，几千张信用卡扣款全部失败"** → 快速熔断、告警、避免用户体验雪崩

```
没有熔断：CyberSource超时5秒 × 1000个并发请求 = 服务器线程耗尽
有熔断：  第3次失败后立即返回错误，触发告警，运维介入
```

**结论：电讯不需要"成本路由"，但需要"故障熔断+告警"，保障月结账单日不崩溃。**

---

## ③ 流程引擎分析

### 现有问题

当前系统支付状态通过 if/else 手动更新，**没有机制拦截非法状态转换**：

```java
// 没有流程引擎时，任何地方都可以直接改状态
transaction.setStatus("SUCCESS");  // 即使之前是 TIMEOUT，也能写进去
paymentMapper.updateById(transaction);  // 没有任何拦截
```

**CSP001xxxxx 事故的根因**：
1. 系统已将状态改为 `TIMEOUT`
2. 之后银行回调 `SUCCESS` 到达
3. 代码没有拦截非法的 `TIMEOUT → SUCCESS` 转换
4. 结果：银行扣款成功，系统状态混乱，客户有损

### Spring StateMachine 的作用

把所有合法的状态迁移**写进配置，非法的自动抛异常**：

```
合法转换（允许）：
  PENDING  → SUCCESS    ✅
  PENDING  → FAILED     ✅
  PENDING  → TIMEOUT    ✅
  SUCCESS  → REFUNDED   ✅

非法转换（自动拦截）：
  TIMEOUT  → SUCCESS    ❌ 抛异常，必须走对账修正流程
  FAILED   → REFUNDED   ❌ 抛异常，失败的订单不能退款
  SUCCESS  → SUCCESS    ❌ 抛异常，防止重复处理回调
```

---

## Steps

### Step 1 — 路由中心：故障熔断（GatewayHealthMonitor）

**目标**：电讯不做成本路由，专注故障熔断保障月结日稳定性

新建 `gateway/GatewayHealthMonitor.java`：

```java
@Component
public class GatewayHealthMonitor {

    // 每个渠道的连续失败计数
    private final Map<PaymentChannel, AtomicInteger> failureCount = new ConcurrentHashMap<>();
    // 熔断状态（true=熔断中）
    private final Map<PaymentChannel, Boolean> circuitOpen = new ConcurrentHashMap<>();
    // 熔断开启时间（用于半开恢复）
    private final Map<PaymentChannel, Long> circuitOpenTime = new ConcurrentHashMap<>();

    private static final int FAILURE_THRESHOLD = 3;       // 连续3次失败触发熔断
    private static final long RECOVERY_TIMEOUT_MS = 60_000; // 60秒后尝试半开恢复

    public void recordSuccess(PaymentChannel channel) {
        failureCount.getOrDefault(channel, new AtomicInteger(0)).set(0);
        circuitOpen.put(channel, false);
    }

    public void recordFailure(PaymentChannel channel) {
        int count = failureCount.computeIfAbsent(channel, k -> new AtomicInteger(0))
                                .incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            circuitOpen.put(channel, true);
            circuitOpenTime.put(channel, System.currentTimeMillis());
            // 触发告警（接钉钉/邮件）
            log.error("[熔断触发] 渠道 {} 连续失败 {} 次，已熔断！", channel.getCode(), count);
        }
    }

    public boolean isCircuitOpen(PaymentChannel channel) {
        Boolean open = circuitOpen.getOrDefault(channel, false);
        if (open) {
            // 判断是否可以尝试半开恢复
            Long openTime = circuitOpenTime.get(channel);
            if (openTime != null && System.currentTimeMillis() - openTime > RECOVERY_TIMEOUT_MS) {
                circuitOpen.put(channel, false); // 允许一次探测
                return false;
            }
        }
        return open;
    }
}
```

### Step 2 — 路由中心：PaymentGatewayRouter 接入熔断检查

修改 `PaymentGatewayRouter.java`，在 `selectGateway()` 和 `getGateway()` 中加入熔断判断：

```java
// selectGateway() 中：自动路由时跳过熔断渠道
public PaymentGateway selectGateway(String paymentMethod) {
    PaymentChannel channel = resolveChannel(paymentMethod);
    if (healthMonitor.isCircuitOpen(channel)) {
        log.warn("[路由拒绝] 渠道 {} 熔断中，拒绝路由", channel.getCode());
        throw new ServiceUnavailableException("支付渠道暂时不可用，请稍后重试");
    }
    return getGateway(channel);
}
```

在支付成功/失败回调中同步更新健康状态：
```java
// PaymentCallbackService 成功处理后
healthMonitor.recordSuccess(channel);

// PaymentCallbackService 处理异常或网关返回错误后  
healthMonitor.recordFailure(channel);
```

### Step 3 — 路由中心：新增 GATEWAY_CIRCUIT_STATUS 表（可选持久化）

如需持久化熔断状态（跨重启保持），新增表：

```sql
CREATE TABLE GATEWAY_CIRCUIT_STATUS (
    CHANNEL_CODE    VARCHAR2(50) PRIMARY KEY,
    IS_OPEN         NUMBER(1)    DEFAULT 0,      -- 0=正常 1=熔断
    FAILURE_COUNT   NUMBER(5)    DEFAULT 0,
    OPEN_TIME       DATE,
    LAST_UPDATE     DATE         DEFAULT SYSDATE
);
```

### Step 4 — 流程引擎：引入 Spring StateMachine 依赖

**文件**：`pom.xml`

```xml
<!-- Spring StateMachine -->
<dependency>
    <groupId>org.springframework.statemachine</groupId>
    <artifactId>spring-statemachine-core</artifactId>
    <version>3.2.1</version>
</dependency>
```

### Step 5 — 流程引擎：定义状态和事件枚举

新建 `statemachine/PaymentStateEnum.java`：

```java
public enum PaymentStateEnum {
    INIT,           // 初始化，交易记录已创建
    PENDING,        // 已提交到银行，等待结果
    PROCESSING,     // 银行处理中（部分渠道有此中间态）
    SUCCESS,        // 支付成功
    FAILED,         // 支付失败（银行拒绝）
    TIMEOUT,        // 系统超时（未收到回调）
    RECONCILING,    // 对账修正中
    REFUNDED        // 已退款
}
```

新建 `statemachine/PaymentEventEnum.java`：

```java
public enum PaymentEventEnum {
    SUBMIT,             // 提交到银行
    BANK_CONFIRM,       // 银行回调成功
    BANK_DECLINE,       // 银行回调失败
    SYSTEM_TIMEOUT,     // 系统超时触发
    RECONCILE_FIX,      // 对账任务修正（特殊事件，需审计）
    REFUND_APPLY,       // 退款申请通过，开始执行
    REFUND_COMPLETE     // 退款执行完成
}
```

### Step 6 — 流程引擎：PaymentStateMachineConfig

新建 `statemachine/PaymentStateMachineConfig.java`：

```java
@Configuration
@EnableStateMachineFactory
public class PaymentStateMachineConfig
        extends StateMachineConfigurerAdapter<PaymentStateEnum, PaymentEventEnum> {

    @Override
    public void configure(StateMachineStateConfigurer<PaymentStateEnum, PaymentEventEnum> states)
            throws Exception {
        states
            .withStates()
            .initial(PaymentStateEnum.INIT)
            .states(EnumSet.allOf(PaymentStateEnum.class))
            .end(PaymentStateEnum.SUCCESS)
            .end(PaymentStateEnum.FAILED)
            .end(PaymentStateEnum.REFUNDED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<PaymentStateEnum, PaymentEventEnum> transitions)
            throws Exception {
        transitions
            // 正常支付流程
            .withExternal().source(INIT).target(PENDING).event(SUBMIT).and()
            .withExternal().source(PENDING).target(SUCCESS).event(BANK_CONFIRM).and()
            .withExternal().source(PENDING).target(FAILED).event(BANK_DECLINE).and()
            .withExternal().source(PENDING).target(TIMEOUT).event(SYSTEM_TIMEOUT).and()
            .withExternal().source(PROCESSING).target(SUCCESS).event(BANK_CONFIRM).and()
            // 对账修正（TIMEOUT 状态可被对账任务修正为 SUCCESS，但走专用事件）
            .withExternal().source(TIMEOUT).target(RECONCILING).event(RECONCILE_FIX).and()
            .withExternal().source(RECONCILING).target(SUCCESS).event(BANK_CONFIRM).and()
            .withExternal().source(RECONCILING).target(FAILED).event(BANK_DECLINE).and()
            // 退款流程
            .withExternal().source(SUCCESS).target(REFUNDED).event(REFUND_COMPLETE);
            // 注意：FAILED → REFUNDED 不存在（自动拦截非法转换）
    }
}
```

### Step 7 — 流程引擎：替换现有状态更新点

将 `PaymentService`、`PaymentCallbackService`、`ReconciliationJob` 中所有 `setStatus()` 调用改为通过状态机事件驱动：

**修改前（旧代码）：**
```java
transaction.setStatus("SUCCESS");
paymentMapper.updateById(transaction);
```

**修改后（新代码）：**
```java
// 获取或恢复该交易的状态机实例
StateMachine<PaymentStateEnum, PaymentEventEnum> sm = 
    stateMachineService.acquireStateMachine(transaction.getTransactionId());

// 发送事件（非法转换自动抛 StateMachineException）
Message<PaymentEventEnum> msg = MessageBuilder
    .withPayload(PaymentEventEnum.BANK_CONFIRM)
    .setHeader("transactionId", transaction.getTransactionId())
    .build();

boolean accepted = sm.sendEvent(msg);
if (!accepted) {
    log.error("[状态机拒绝] 事件 BANK_CONFIRM 在状态 {} 下不合法，transactionId={}",
        sm.getState().getId(), transaction.getTransactionId());
    throw new IllegalStateTransitionException("非法支付状态转换，请联系运维");
}
```

**对账任务修正（专用 RECONCILE_FIX 事件，留审计）：**
```java
// ReconciliationJob 中修正 TIMEOUT → SUCCESS
sm.sendEvent(PaymentEventEnum.RECONCILE_FIX);  // 先进入 RECONCILING 状态（写审计日志）
sm.sendEvent(PaymentEventEnum.BANK_CONFIRM);   // 再确认为 SUCCESS
// PREVIOUS_STATUS 字段记录修正前的状态，供财务审计
```

---

## Further Considerations

1. **熔断后用户体验**：CyberSource 熔断时，信用卡用户应看到"当前信用卡服务繁忙，请稍后重试或使用其他支付方式"，而不是技术错误码。需要在 `GlobalExceptionHandler` 中将 `ServiceUnavailableException` 转换为友好提示。

2. **Spring StateMachine 复杂度代价**：引入后调试难度上升，需要团队熟悉其持久化机制（`StateMachinePersist`）。**如果团队规模小（<5人），可用更轻量替代方案**：自定义 `PaymentStatusValidator` 工具类，用 `Map<Status, Set<Status>> allowedTransitions` 替代，同样能拦截非法转换，学习成本接近零。

3. **电讯月结与状态机的关键边界**：批量扣费场景（月底自动扣费）会产生大量并发状态转换，状态机实例是否需要持久化到数据库？建议：**每笔交易独立一个状态机实例**，通过 `transactionId` 作为 machineId，持久化到 `PAYMENT_TRANSACTION` 表的 `PAYMENT_STATUS` 字段，避免内存状态丢失。

4. **跨境漫游路由优先级**：跨境漫游缴费需自动匹配支持外汇结算的渠道（CyberSource 多货币），此业务路由规则比健康检查优先级更高，需在 `selectGateway()` 前置处理，不受熔断影响（熔断只影响同等能力渠道的切换）。

5. **路由成功率提升目标（98.2% → 99.5%）的实现路径**：
   - **Phase 1**（Plan B 动态网关切换）：健康熔断 + 同类渠道故障自动告警，减少因单点故障造成的批量失败，预计提升 0.5~0.8%
   - **Phase 2**（Plan A 多商户号切换）：同一网关（如 CyberSource）申请多个 MID，单 MID 触发 Visa Chargeback 阈值时自动切换，需向 CyberSource EBC 申请，有合同审批周期，预计再提升 0.3~0.5%


