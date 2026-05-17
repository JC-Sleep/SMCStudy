# Plan: 新增第三方支付网关支持 Visa/MC/Amex 直接扣款

## 背景

当前系统已有 `PaymentGatewayRouter` 策略模式架构（见`多渠道支付扩展设计方案.md`），已实现渠道：
- `SCB` —— 渣打银行（特殊合作银行，部分页面专属）
- `ALIPAY` —— 支付宝
- `CCB` —— 建设银行

**目标**：新增一个通用国际信用卡网关（Stripe），支持 Visa / Mastercard / Amex 直接扣款。  
渣打渠道仅保留给渣打专属页面，普通支付页面走新网关，两者并行互不影响。

---

## Step 1 — 新增 PaymentChannel 枚举值

文件：`PaymentChannel.java`

```java
STRIPE("STRIPE", "Stripe", "stripe");
```

`supportsPaymentMethod` 支持：`VISA`、`MASTERCARD`、`AMEX`、`CARD`（泛指信用卡/借记卡）

---

## Step 2 — 实现 StripeGatewayClient

文件：`StripeGatewayClient.java`（继承 `AbstractPaymentGateway`）

关键实现点：
- `createPayment()` —— 调用 Stripe `POST /v1/payment_intents`，返回 `client_secret` 给前端
- `queryTransactionStatus()` —— 调用 `GET /v1/payment_intents/{id}`，映射 `succeeded` → `SUCCESS`
- `refund()` —— 调用 `POST /v1/refunds`
- `verifyCallback()` —— 验证 `Stripe-Signature` Header（`Webhook-Signing-Secret`）
- `parseCallbackData()` —— 解析 Stripe Webhook Event（`payment_intent.succeeded` / `payment_intent.payment_failed`）
- `isAvailable()` —— 检查 `stripe.api.key` 是否已配置
- `getPriority()` —— 返回 `10`（低于 SCB 的 `1`，SCB 专属页面通过 channel 强制指定，不走 priority）

Stripe 状态映射：

| Stripe status | 系统状态 |
|---------------|---------|
| `requires_payment_method` | `PENDING` |
| `requires_confirmation` | `PENDING` |
| `processing` | `PENDING` |
| `succeeded` | `SUCCESS` |
| `canceled` | `FAILED` |
| `requires_action` | `PENDING`（3DS验证中） |

---

## Step 3 — 修改 PaymentGatewayRouter 路由规则

文件：`PaymentGatewayRouter.java`

修改 `selectGateway(String paymentMethod)` 逻辑：

```
if paymentMethod in [VISA, MASTERCARD, AMEX, CARD, CREDIT_CARD]
    → 路由到 STRIPE
if paymentMethod in [ALIPAY, ALIPAY_WAP]
    → 路由到 ALIPAY
if paymentMethod in [CCB, DEBIT_CARD]
    → 路由到 CCB
// SCB 不参与自动路由，仅在 channel=SCB 时由 getGateway(SCB) 强制指定
```

新增重载方法 `selectGateway(String paymentMethod, String channelOverride)`：
- 若 `channelOverride` 不为 null，直接 `getGateway(channel)` 返回对应网关（渣打专属页面使用此路径）
- 否则走上方自动路由逻辑

---

## Step 4 — application.yml 新增 Stripe 配置

```yaml
stripe:
  api:
    key: ${STRIPE_API_KEY:}           # Secret Key，后端使用
    publishable-key: ${STRIPE_PUBLISHABLE_KEY:}   # 前端 Stripe.js 使用
  webhook:
    secret: ${STRIPE_WEBHOOK_SECRET:} # Webhook Signing Secret
  currency: HKD
```

UAT/沙箱 key 前缀：`sk_test_xxx` / `pk_test_xxx`

---

## Step 5 — 新增回调端点

文件：`PaymentCallbackController.java`（在现有文件新增方法）

```
POST /api/payment/callback/stripe
```

- Header：`Stripe-Signature`
- Body：Stripe Webhook JSON Event
- 调用 `stripeGatewayClient.verifyCallback()` 验签
- 解析 `payment_intent.succeeded` / `payment_intent.payment_failed`
- 更新 `PAYMENT_TRANSACTION` 状态，写 `PAYMENT_CALLBACK_LOG`
- 响应 `HTTP 200 {"status":"SUCCESS"}` （Stripe 要求200，否则会重试）

---

## Step 6 — 前端区分渣打专属 vs 普通信用卡

### 普通信用卡支付页面（走 Stripe）

Request body：
```json
{
  "orderReference": "CSP001234567",
  "amount": 176.00,
  "currency": "HKD",
  "paymentMethod": "VISA"
}
```
不传 `channel`，Router 自动路由到 Stripe。

Response 返回 `client_secret`，前端用 **Stripe.js Hosted Fields** 完成 3DS 验证 + 扣款，卡号不经过商户后端。

### 渣打专属页面（走 SCB）

Request body：
```json
{
  "orderReference": "CSP001234567",
  "amount": 176.00,
  "currency": "HKD",
  "channel": "SCB"
}
```
强制指定 `channel=SCB`，走渣打原有流程，不影响现有逻辑。

---

## Step 7 — 退款兼容性确认

现有 `RefundService` 通过 `PaymentGatewayRouter.getGateway(channel)` 获取网关执行退款。  
`PAYMENT_TRANSACTION` 表已有 `GATEWAY_NAME` 字段存储渠道代码。  
只要 `StripeGatewayClient.refund()` 实现完整，退款审批流（财务审批 → 执行退款）**无需改动**。

---

## Further Considerations

### 为什么选 Stripe 而不是 Adyen？
| 对比项 | Stripe | Adyen |
|--------|--------|-------|
| 开通难度 | 自助注册，当天可用 | 需商务合同，审批周期长 |
| HK 支持 | 完整（HKD、FPS、本地卡） | 完整但配置复杂 |
| SDK 文档 | 极佳，Java SDK 完善 | 良好，配置项更多 |
| 手续费 | 2.9% + HKD 2.35 | 按合同，量大更优 |
| 适用场景 | 中小规模，快速上线 | 大规模，多国部署 |

若公司已有 Adyen 合同，步骤相同，只替换 `StripeGatewayClient` 为 `AdyenGatewayClient`，API 格式不同但架构不变。

### PCI DSS 合规（重要）
必须使用 Stripe.js 的 **Hosted Fields / Payment Element**，卡号由 Stripe 直接接收，不经过商户后端。  
若卡号经过商户后端，需通过 PCI DSS SAQ D 认证，成本极高。

### 3DS 2.0 强制验证
Stripe 在 HK 自动处理 3DS（`requires_action` 状态），前端用 `stripe.handleNextAction(client_secret)` 即可。  
通过 3DS 后 Chargeback 责任转移到发卡行，商户免责。

### 测试 Cards（Stripe Sandbox）
| 卡号 | 结果 |
|------|------|
| `4242 4242 4242 4242` | 成功 |
| `4000 0025 0000 3155` | 需要 3DS 验证 |
| `4000 0000 0000 9995` | 余额不足 |
| `3714 496353 98431` | Amex 成功 |

---

## 影响范围

| 组件 | 变更类型 | 备注 |
|------|---------|------|
| `PaymentChannel.java` | 新增枚举值 | 加 `STRIPE` |
| `StripeGatewayClient.java` | 新增文件 | 实现 `AbstractPaymentGateway` |
| `PaymentGatewayRouter.java` | 修改路由逻辑 | 新增 channelOverride 参数 |
| `PaymentCallbackController.java` | 新增回调端点 | `/callback/stripe` |
| `application.yml` | 新增配置 | `stripe.*` |
| `PaymentService.java` | 微调 | 透传 channelOverride 参数 |
| 渣打专属页面（前端） | 修改 | request 加 `"channel":"SCB"` |
| 普通信用卡页面（前端） | 修改 | 集成 Stripe.js Payment Element |
| `RefundService.java` | **不需要改动** | 已通过 GATEWAY_NAME 路由 |
| `PAYMENT_TRANSACTION` 表 | **不需要改动** | `GATEWAY_NAME` 字段已存在 |
