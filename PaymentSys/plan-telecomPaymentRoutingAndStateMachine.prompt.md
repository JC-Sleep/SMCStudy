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

---

## ③ 流程引擎：为什么 Spring StateMachine 不适合支付系统

### 根本原因：Spring StateMachine 是"有状态机器"，支付需要"无状态校验器"

```
Spring StateMachine 的设计假设：
  一个"对象"（订单/会话/设备）有一个长期运行的状态机实例
  状态机对象常驻内存 or 需要持久化
  适用场景：工作流引擎、设备连接管理、游戏角色状态

支付系统的实际情况：
  每笔交易的"当前状态"存在数据库里
  不需要常驻内存的机器实例
  需要的是：每次改状态前，校验这个转换合不合法
  校验完，把新状态写DB，就结束了
```

### Spring StateMachine 的具体问题

| 问题 | 说明 |
|------|------|
| **实例管理复杂** | 每笔交易需要一个独立 `StateMachine` 实例，并发1000笔 = 内存里1000个机器实例 |
| **持久化困难** | 需要实现 `StateMachinePersist` 把机器状态写DB，代码量巨大 |
| **调试地狱** | 状态机有自己的 ApplicationContext，日志混乱，NPE 难以追踪 |
| **3.x 维护危机** | Spring StateMachine 3.x 文档稀疏，社区反映 bug 多，大厂早已弃用 |
| **80% 功能用不上** | 层级状态、并发Region、历史伪状态……对支付7个状态完全浪费 |
| **启动慢** | StateMachineFactory 初始化耗时，影响单元测试 |

> **Alibaba/蚂蚁/美团实践**：均已从 Spring StateMachine 迁出，要么自研，要么用 COLA StateMachine。

---

## ③ 流程引擎方案选型

### 本系统支付状态全图（10个状态，11种合法转换）

```
                        ┌─────────────────────────────────────────────────────────┐
                        │                   支付状态机                             │
                        └─────────────────────────────────────────────────────────┘

               [SUBMIT]              [BANK_CONFIRM]
INIT ─────────────────▶ PENDING ──────────────────▶ SUCCESS ──┐
                           │                            │      │ [REFUND_APPLY]
                           │ [BANK_DECLINE]             │      ▼
                           ├───────────────▶ FAILED(终态)  REFUNDING
                           │                                │       │
                           │ [SYSTEM_TIMEOUT]     [PARTIAL_REFUND] [REFUND_COMPLETE]
                           ▼                          ▼              ▼
                        TIMEOUT          PARTIALLY_REFUNDED      REFUNDED(终态)
                           │                    │ [REFUND_APPLY]    │
                           │ [RECONCILE_START]  ╰──────────────▶ REFUNDING
                           ▼                                [REFUND_FAIL]
                       RECONCILING                              ▼
                           │                             REFUND_FAILED
                    ┌──────┴──────┐                          │ [REFUND_APPLY]
            [RECON_OK]     [RECON_FAIL]                      ▼
               ▼               ▼                          REFUNDING
            SUCCESS(终)    FAILED(终)
```

**非法转换示例（必须被拦截）：**
- `TIMEOUT → SUCCESS`（银行迟到回调绕过对账，导致账务混乱）
- `FAILED → REFUNDED`（失败的订单不能退款）
- `SUCCESS → SUCCESS`（防止重复处理回调）
- `REFUNDED → REFUNDING`（已退款不能再退）

---

## ③-A 方案一：COLA StateMachine（推荐）

### 为什么大厂用 COLA

COLA StateMachine 是阿里巴巴 COLA 4.x 框架的一个独立组件，核心代码仅 ~400行，在淘宝/支付宝/钉钉等亿级系统验证过：

| 对比项 | Spring StateMachine | COLA StateMachine | 自研 |
|--------|---------------------|-------------------|------|
| jar 体积 | ~5MB（依赖链长） | ~100KB（独立jar） | 0KB（内置） |
| 学习成本 | 高（文档差，坑多） | 低（DSL清晰，30分钟上手） | 极低（自己写的） |
| 是否有状态 | 有状态（需持久化实例） | **无状态**（每次传入当前状态） | 无状态 |
| 支持 guard/action | ✅ | ✅（when/perform） | 按需实现 |
| 大厂背书 | 已弃用 | Alibaba/蚂蚁/钉钉 | 各大厂均有自研 |
| 调试难度 | 高 | 低 | 极低 |

### COLA StateMachine 核心设计理念

```
Spring StateMachine：机器自己记住当前在哪个状态，你问它
COLA StateMachine ：机器是无状态的定义，你每次告诉它"我现在在哪个状态，发生了什么事"
                    它告诉你"你应该去哪个状态，帮你执行动作"，然后你自己写DB
```

### Step 4A — pom.xml 新增 COLA 依赖

```xml
<!-- COLA StateMachine - 阿里 COLA 框架状态机组件（仅此一个jar，无传递依赖） -->
<dependency>
    <groupId>com.alibaba.cola</groupId>
    <artifactId>cola-component-statemachine</artifactId>
    <version>4.3.2</version>
</dependency>
```

### Step 5A — 新建 PaymentEvent 枚举（触发状态转换的事件）

新建 `statemachine/PaymentEvent.java`：

```java
package sys.smc.payment.statemachine;

public enum PaymentEvent {
    SUBMIT,              // 提交到银行（INIT → PENDING）
    BANK_CONFIRM,        // 银行回调成功（PENDING → SUCCESS）
    BANK_DECLINE,        // 银行回调失败（PENDING → FAILED）
    SYSTEM_TIMEOUT,      // 系统超时（PENDING → TIMEOUT）
    RECONCILE_START,     // 对账任务开始（TIMEOUT → RECONCILING）
    RECON_SUCCESS,       // 对账确认成功（RECONCILING → SUCCESS）
    RECON_FAIL,          // 对账确认失败（RECONCILING → FAILED）
    REFUND_APPLY,        // 退款申请通过（SUCCESS/PARTIALLY_REFUNDED/REFUND_FAILED → REFUNDING）
    REFUND_COMPLETE,     // 全额退款完成（REFUNDING → REFUNDED）
    PARTIAL_REFUND,      // 部分退款完成（REFUNDING → PARTIALLY_REFUNDED）
    REFUND_FAIL          // 退款失败（REFUNDING → REFUND_FAILED）
}
```

### Step 6A — 新建 TransitionContext（转换上下文，传给 guard/action）

新建 `statemachine/TransitionContext.java`：

```java
package sys.smc.payment.statemachine;

import lombok.Builder;
import lombok.Data;
import sys.smc.payment.entity.PaymentTransaction;

@Data
@Builder
public class TransitionContext {
    private PaymentTransaction transaction;
    private String operator;         // 触发人（回调/财务/对账Job）
    private String remark;           // 备注
    private boolean signatureValid;  // 签名验证结果（回调场景用）
}
```

### Step 7A — PaymentStateMachineConfig（核心配置）

新建 `statemachine/PaymentStateMachineConfig.java`：

```java
package sys.smc.payment.statemachine;

import com.alibaba.cola.statemachine.StateMachine;
import com.alibaba.cola.statemachine.StateMachineBuilder;
import com.alibaba.cola.statemachine.StateMachineBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sys.smc.payment.enums.PaymentStatus;

import static sys.smc.payment.enums.PaymentStatus.*;
import static sys.smc.payment.statemachine.PaymentEvent.*;

@Configuration
@Slf4j
public class PaymentStateMachineConfig {

    public static final String MACHINE_ID = "paymentStateMachine";

    @Bean
    public StateMachine<PaymentStatus, PaymentEvent, TransitionContext> paymentStateMachine() {

        StateMachineBuilder<PaymentStatus, PaymentEvent, TransitionContext> builder =
            StateMachineBuilderFactory.create();

        // ── 正常支付流程 ──────────────────────────────────────────────────
        builder.externalTransition()
            .from(INIT).to(PENDING).on(SUBMIT)
            .perform((from, to, event, ctx) ->
                log.info("[状态机] {} INIT→PENDING txn={}", event, ctx.getTransaction().getTransactionId()));

        builder.externalTransition()
            .from(PENDING).to(SUCCESS).on(BANK_CONFIRM)
            .when(ctx -> ctx.isSignatureValid())   // guard：签名必须有效
            .perform((from, to, event, ctx) ->
                log.info("[状态机] 支付成功 txn={}", ctx.getTransaction().getTransactionId()));

        builder.externalTransition()
            .from(PENDING).to(FAILED).on(BANK_DECLINE)
            .perform((from, to, event, ctx) ->
                log.warn("[状态机] 银行拒绝 txn={}", ctx.getTransaction().getTransactionId()));

        builder.externalTransition()
            .from(PENDING).to(TIMEOUT).on(SYSTEM_TIMEOUT);

        // ── 对账修正流程 ──────────────────────────────────────────────────
        // TIMEOUT 先进 RECONCILING（有审计记录），再由对账Job确认最终状态
        // 注意：TIMEOUT 不能直接 → SUCCESS，必须经过 RECONCILING，这是关键安全约束
        builder.externalTransition()
            .from(TIMEOUT).to(RECONCILING).on(RECONCILE_START)
            .perform((from, to, event, ctx) ->
                log.warn("[状态机][对账] TIMEOUT→RECONCILING txn={} operator={}",
                    ctx.getTransaction().getTransactionId(), ctx.getOperator()));

        builder.externalTransition()
            .from(RECONCILING).to(SUCCESS).on(RECON_SUCCESS)
            .perform((from, to, event, ctx) ->
                log.warn("[状态机][对账修正] RECONCILING→SUCCESS txn={} operator={}",
                    ctx.getTransaction().getTransactionId(), ctx.getOperator()));

        builder.externalTransition()
            .from(RECONCILING).to(FAILED).on(RECON_FAIL);

        // ── 退款流程 ─────────────────────────────────────────────────────
        // SUCCESS、PARTIALLY_REFUNDED、REFUND_FAILED 都可以发起退款申请
        builder.externalTransitions()
            .fromAmong(SUCCESS, PARTIALLY_REFUNDED, REFUND_FAILED)
            .to(REFUNDING).on(REFUND_APPLY);

        builder.externalTransition()
            .from(REFUNDING).to(REFUNDED).on(REFUND_COMPLETE)
            .perform((from, to, event, ctx) ->
                log.info("[状态机] 全额退款完成 txn={}", ctx.getTransaction().getTransactionId()));

        builder.externalTransition()
            .from(REFUNDING).to(PARTIALLY_REFUNDED).on(PARTIAL_REFUND)
            .perform((from, to, event, ctx) ->
                log.info("[状态机] 部分退款完成 txn={}", ctx.getTransaction().getTransactionId()));

        builder.externalTransition()
            .from(REFUNDING).to(REFUND_FAILED).on(REFUND_FAIL)
            .perform((from, to, event, ctx) ->
                log.error("[状态机] 退款失败，需人工介入 txn={}", ctx.getTransaction().getTransactionId()));

        // ── 所有未定义转换自动被拒绝（COLA 返回原状态，包装层检测后抛异常）──
        return builder.build(MACHINE_ID);
    }
}
```

### Step 8A — PaymentStateMachineService（对外使用入口，封装非法转换检测）

新建 `statemachine/PaymentStateMachineService.java`：

```java
package sys.smc.payment.statemachine;

import com.alibaba.cola.statemachine.StateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.IllegalStateTransitionException;

@Service
@Slf4j
public class PaymentStateMachineService {

    @Autowired
    private StateMachine<PaymentStatus, PaymentEvent, TransitionContext> stateMachine;

    /**
     * 触发状态转换（非法转换抛 IllegalStateTransitionException）
     *
     * COLA StateMachine 是无状态的：你传入当前状态和事件，它返回新状态。
     * 实际状态存储仍在 DB 的 PAYMENT_TRANSACTION.PAYMENT_STATUS 字段。
     *
     * @param current   当前状态（从DB读取）
     * @param event     触发事件
     * @param ctx       上下文（含 transaction / operator 等）
     * @return          转换后的新状态（调用方负责写DB）
     */
    public PaymentStatus fireEvent(PaymentStatus current, PaymentEvent event, TransitionContext ctx) {
        log.debug("[状态机] 尝试转换: {} + [{}], txn={}",
            current, event, ctx.getTransaction().getTransactionId());

        PaymentStatus newStatus = stateMachine.fireEvent(current, event, ctx);

        // COLA 未找到转换时返回原状态（不抛异常）——我们在这里检测并抛出
        if (newStatus == current) {
            log.error("[状态机] 非法状态转换被拒绝: {} -[{}]→ ?, txn={}",
                current, event, ctx.getTransaction().getTransactionId());
            throw new IllegalStateTransitionException(
                String.format("非法支付状态转换: %s + 事件[%s] 不存在合法目标状态", current, event));
        }

        log.info("[状态机] 转换成功: {} → {} (事件: {}) txn={}",
            current, newStatus, event, ctx.getTransaction().getTransactionId());
        return newStatus;
    }

    /**
     * 检查转换是否合法（不执行，不抛异常，仅查询）
     * 用于前端预校验或 if-else 判断
     */
    public boolean canTransition(PaymentStatus current, PaymentEvent event) {
        try {
            TransitionContext dryRunCtx = TransitionContext.builder()
                .signatureValid(true).build();
            PaymentStatus result = stateMachine.fireEvent(current, event, dryRunCtx);
            return result != current;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### Step 9A — 替换现有状态更新点（使用方式）

**修改前（散落各处的直接 setStatus）：**
```java
// PaymentCallbackServiceEnhanced.java
transaction.setPaymentStatus("SUCCESS");  // ❌ 没有任何校验
transactionMapper.updateById(transaction);
```

**修改后（通过状态机校验）：**
```java
// PaymentCallbackServiceEnhanced.java 修改示例
@Autowired
private PaymentStateMachineService stateMachineService;

// 构建上下文
TransitionContext ctx = TransitionContext.builder()
    .transaction(transaction)
    .operator("CALLBACK_" + channel.getCode())
    .signatureValid(signatureValid)
    .build();

// 触发事件（非法转换自动抛异常，不会继续写DB）
PaymentEvent event = "SUCCESS".equals(callbackData.getPaymentStatus())
    ? PaymentEvent.BANK_CONFIRM : PaymentEvent.BANK_DECLINE;

PaymentStatus newStatus = stateMachineService.fireEvent(
    PaymentStatus.valueOf(transaction.getPaymentStatus()), event, ctx);

// 状态机通过校验，写DB
transaction.setPaymentStatus(newStatus.name());
transactionMapper.updateById(transaction);
```

**对账任务修正（走专用 RECONCILE 事件，绕过直接 TIMEOUT→SUCCESS）：**
```java
// ReconciliationJob 修改示例
// ❌ 原来：直接写 SUCCESS，不经任何校验
// transaction.setPaymentStatus("SUCCESS");

// ✅ 现在：必须经过 RECONCILING 中间状态，留审计踪迹
TransitionContext ctx = TransitionContext.builder()
    .transaction(transaction)
    .operator("RECONCILIATION_JOB")
    .remark("对账修正: 银行实际已扣款")
    .build();

// Step1: TIMEOUT → RECONCILING（写一条 RECONCILING 记录到 PAYMENT_CALLBACK_LOG）
stateMachineService.fireEvent(TIMEOUT, RECONCILE_START, ctx);
transaction.setPaymentStatus(RECONCILING.name());
transactionMapper.updateById(transaction);

// Step2: RECONCILING → SUCCESS（最终确认）
stateMachineService.fireEvent(RECONCILING, RECON_SUCCESS, ctx);
transaction.setPaymentStatus(SUCCESS.name());
transactionMapper.updateById(transaction);
```

---

## ③-B 方案二：自研轻量状态机（极简，团队可完全掌控）

### 设计思路

对支付这种**线性有限状态流**，80行代码就能解决问题。  
不引入任何第三方框架，团队成员5分钟看懂全部代码，零调试成本。

这是美团、滴滴早期支付系统的做法，后期业务复杂化后再升级到 COLA 或自研框架。

### Step 4B — PaymentTransition（转换定义）

新建 `statemachine/PaymentTransition.java`：

```java
package sys.smc.payment.statemachine;

import lombok.Builder;
import lombok.Data;
import sys.smc.payment.enums.PaymentStatus;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 支付状态转换定义
 * 一条转换 = 源状态 + 目标状态 + （可选）条件校验 + （可选）转换动作
 */
@Data
@Builder
public class PaymentTransition {
    private PaymentStatus from;
    private PaymentStatus to;
    private String description;
    private Predicate<TransitionContext> guard;   // 条件（为 null 时无条件允许）
    private Consumer<TransitionContext> action;   // 动作（为 null 时无动作）
}
```

### Step 5B — PaymentStateMachine（核心状态机，60行）

新建 `statemachine/PaymentStateMachine.java`：

```java
package sys.smc.payment.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.IllegalStateTransitionException;

import javax.annotation.PostConstruct;
import java.util.*;

import static sys.smc.payment.enums.PaymentStatus.*;

/**
 * 自研支付状态机
 *
 * 设计原则：
 *  - 无状态：不持有任何交易的当前状态，状态在 DB 里
 *  - 线程安全：transitions Map 在 @PostConstruct 初始化后只读
 *  - 白名单机制：只有明确声明的转换才合法，其余全部拒绝
 */
@Component
@Slf4j
public class PaymentStateMachine {

    // Key: fromState, Value: list of defined transitions from that state
    private final Map<PaymentStatus, List<PaymentTransition>> transitions = new EnumMap<>(PaymentStatus.class);

    @PostConstruct
    public void init() {
        // ─── 正常支付流程 ───────────────────────────────────────────────
        register(INIT, PENDING,
            "提交至银行");

        register(PENDING, SUCCESS,
            "银行确认成功",
            ctx -> ctx.isSignatureValid(),          // guard: 签名必须有效
            ctx -> log.info("[SM] 支付成功 txn={}", ctx.getTransaction().getTransactionId()));

        register(PENDING, FAILED,
            "银行拒绝扣款");

        register(PENDING, TIMEOUT,
            "系统超时未收到回调");

        // ─── 对账修正流程（TIMEOUT 必须经过 RECONCILING，禁止直接→SUCCESS）──
        register(TIMEOUT, RECONCILING,
            "对账任务介入",
            null,
            ctx -> log.warn("[SM][对账] 进入对账状态 txn={} operator={}",
                ctx.getTransaction().getTransactionId(), ctx.getOperator()));

        register(RECONCILING, SUCCESS,
            "对账确认成功",
            null,
            ctx -> log.warn("[SM][对账修正] RECONCILING→SUCCESS txn={}", ctx.getTransaction().getTransactionId()));

        register(RECONCILING, FAILED,
            "对账确认失败");

        // ─── 退款流程 ────────────────────────────────────────────────────
        register(SUCCESS, REFUNDING,
            "退款申请通过");

        register(PARTIALLY_REFUNDED, REFUNDING,
            "再次申请部分退款");

        register(REFUND_FAILED, REFUNDING,
            "退款失败重试");

        register(REFUNDING, REFUNDED,
            "全额退款完成",
            null,
            ctx -> log.info("[SM] 全额退款完成 txn={}", ctx.getTransaction().getTransactionId()));

        register(REFUNDING, PARTIALLY_REFUNDED,
            "部分退款完成");

        register(REFUNDING, REFUND_FAILED,
            "退款执行失败，需人工干预",
            null,
            ctx -> log.error("[SM] 退款失败！需人工核查 txn={}", ctx.getTransaction().getTransactionId()));

        log.info("[支付状态机] 初始化完成，共注册 {} 条合法转换",
            transitions.values().stream().mapToInt(List::size).sum());
    }

    /**
     * 执行状态转换（核心方法）
     *
     * @param from 当前状态（从DB读取）
     * @param to   目标状态
     * @param ctx  上下文（含交易对象、操作人等）
     * @throws IllegalStateTransitionException 非法转换或条件不满足时抛出
     */
    public void transition(PaymentStatus from, PaymentStatus to, TransitionContext ctx) {
        List<PaymentTransition> available = transitions.getOrDefault(from, Collections.emptyList());

        // 查找匹配的转换定义（白名单查找）
        Optional<PaymentTransition> matched = available.stream()
            .filter(t -> t.getTo() == to)
            .findFirst();

        if (matched.isEmpty()) {
            log.error("[SM] 非法状态转换: {} → {} txn={} 所有合法目标: {}",
                from, to,
                ctx.getTransaction() != null ? ctx.getTransaction().getTransactionId() : "N/A",
                getAvailableTargets(from));
            throw new IllegalStateTransitionException(
                String.format("非法支付状态转换: %s → %s（不在合法转换表中）", from, to));
        }

        PaymentTransition t = matched.get();

        // 执行 Guard 条件检验
        if (t.getGuard() != null && !t.getGuard().test(ctx)) {
            throw new IllegalStateTransitionException(
                String.format("状态转换条件不满足: %s → %s，原因: %s", from, to, t.getDescription()));
        }

        // 执行转换动作（日志、通知等）
        if (t.getAction() != null) {
            t.getAction().accept(ctx);
        }

        log.debug("[SM] 转换成功: {} → {} ({})", from, to, t.getDescription());
    }

    /**
     * 预检查：转换是否合法（不执行，不抛异常）
     */
    public boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return transitions.getOrDefault(from, Collections.emptyList())
            .stream().anyMatch(t -> t.getTo() == to);
    }

    /**
     * 查询某状态的所有合法目标状态
     */
    public Set<PaymentStatus> getAvailableTargets(PaymentStatus from) {
        Set<PaymentStatus> result = new LinkedHashSet<>();
        transitions.getOrDefault(from, Collections.emptyList())
            .forEach(t -> result.add(t.getTo()));
        return result;
    }

    // ─── 注册辅助方法 ─────────────────────────────────────────────────────

    private void register(PaymentStatus from, PaymentStatus to, String desc) {
        register(from, to, desc, null, null);
    }

    private void register(PaymentStatus from, PaymentStatus to, String desc,
                          java.util.function.Predicate<TransitionContext> guard,
                          java.util.function.Consumer<TransitionContext> action) {
        transitions.computeIfAbsent(from, k -> new ArrayList<>())
            .add(PaymentTransition.builder()
                .from(from).to(to).description(desc)
                .guard(guard).action(action)
                .build());
    }
}
```

### Step 6B — 使用方式（与方案A基本相同）

```java
// 注入状态机
@Autowired
private PaymentStateMachine stateMachine;

// 构建上下文
TransitionContext ctx = TransitionContext.builder()
    .transaction(transaction)
    .operator("CALLBACK")
    .signatureValid(true)
    .build();

// 校验并执行转换（非法时自动抛异常，阻止后续DB写入）
stateMachine.transition(
    PaymentStatus.valueOf(transaction.getPaymentStatus()),
    PaymentStatus.SUCCESS,
    ctx
);

// 只有通过校验才到这里
transaction.setPaymentStatus(PaymentStatus.SUCCESS.name());
transactionMapper.updateById(transaction);
```

---

## 方案对比与选择建议

### 三方案全面对比

| 对比维度 | Spring StateMachine | COLA StateMachine（方案A） | 自研（方案B） |
|----------|---------------------|---------------------------|--------------|
| **jar 体积** | ~5MB | ~100KB | 0（内置） |
| **学习成本** | 高（文档差） | 中（30分钟） | 极低（自己写的） |
| **状态管理** | 实例持久化，复杂 | 无状态，DB 存状态 | 无状态，DB 存状态 |
| **guard/action** | ✅ | ✅ | ✅（Lambda） |
| **层级状态** | ✅（不需要） | ❌（不需要） | ❌（不需要） |
| **并发Region** | ✅（不需要） | ❌（不需要） | ❌（不需要） |
| **调试难度** | 极高 | 低 | 极低 |
| **大厂背书** | 已弃用 | Alibaba / 蚂蚁 | 各大厂均有 |
| **可视化** | 有（复杂） | 有（`generatePlantUML()`） | 需手写图 |
| **活跃维护** | 很少 | 活跃 | — |
| **适用团队** | ❌ 不推荐 | ✅ 中大型团队 | ✅ 小型团队 |

### **推荐决策**

```
团队人数 ≤ 5人，或状态数 ≤ 15个？
    → 选方案B（自研），零依赖，代码可完全掌控
    → 日后需要时可无缝迁移到方案A

团队人数 > 5人，或未来需要 PlantUML 可视化状态图？
    → 选方案A（COLA），大厂验证，DSL 清晰，jar 小

Spring StateMachine？
    → 不选，直接排除
```

> 对于本电讯支付系统：当前 **10个状态，11种转换**，推荐从**方案B（自研）**开始，  
> 等到状态数超过20个或需要复杂 guard 链时再升级方案A，迁移成本几乎为零。

---

## Further Considerations

1. **熔断后用户体验**：CyberSource 熔断时，信用卡用户应看到"当前信用卡服务繁忙，请稍后重试或使用其他支付方式"。在 `GlobalExceptionHandler` 中将 `ServiceUnavailableException` 转换为友好提示。

2. **电讯月结与状态机的关键边界**：批量自动扣费月底并发高，状态机是无状态的不受影响。唯一注意：乐观锁 `version` 字段配合状态机一起使用，确保"校验通过 → 写DB"之间不被其他线程抢占。

3. **跨境漫游路由优先级**：跨境漫游缴费需自动匹配支持外汇结算的渠道（CyberSource 多货币），此业务路由规则比健康检查优先级更高，在 `selectGateway()` 前置处理，不受熔断影响。

4. **路由成功率提升目标（98.2% → 99.5%）的实现路径**：
   - **Phase 1**（健康熔断+告警）：减少单点故障导致的批量失败，预计提升 0.5~0.8%
   - **Phase 2**（多商户号 MID 切换）：同一 CyberSource 账号申请多个 MID，Chargeback 超阈值时切换，需 EBC 合同申请，预计再提升 0.3~0.5%

5. **状态机的唯一危险**：无论哪个方案，状态机只是"校验层"，**DB 才是状态真相来源**。  
   一定要确保"状态机通过校验 → 数据库更新"是**原子操作**（同一事务，或乐观锁兜底），  
   否则状态机通过了但 DB 更新失败，会出现"状态机说SUCCEss但DB还是PENDING"的不一致。


