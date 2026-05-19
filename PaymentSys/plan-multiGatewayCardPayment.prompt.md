# Plan: 新增第三方支付网关支持 Visa/MC/Amex 直接扣款

## 背景

当前系统已有 `PaymentGatewayRouter` 策略模式架构（见`多渠道支付扩展设计方案.md`），已实现渠道：
- `SCB` —— 渣打银行（特殊合作银行，部分页面专属）
- `ALIPAY` —— 支付宝
- `CCB` —— 建设银行

**目标**：新增 CyberSource 作为通用国际信用卡网关，支持 Visa / Mastercard / Amex 直接扣款。  
CyberSource 是 Visa 旗下全球主流支付网关，广泛用于香港及国际商户。  
渣打渠道仅保留给渣打专属页面，普通信用卡支付页面走 CyberSource，两者并行互不影响。

---

## ⚠️ CyberSource vs Stripe 关键差异（换网关前必读）

| 对比项 | Stripe | CyberSource |
|--------|--------|-------------|
| 认证方式 | Bearer Token（`Authorization: Bearer sk_xxx`） | HTTP Signature（HMAC-SHA256，复杂多步骤签名） |
| 身份凭证 | API Key | Merchant ID + Key ID + Shared Secret（三个都要） |
| 支付创建 API | `POST /v1/payment_intents` | `POST /pts/v2/payments` |
| 查询 API | `GET /v1/payment_intents/{id}` | `GET /tss/v2/transactions/{id}` |
| 退款 API | `POST /v1/refunds` | `POST /pts/v2/refunds`（body 带原始 `paymentId`） |
| Webhook 验签 Header | `Stripe-Signature` | `v-c-signature` |
| 前端卡号 SDK | Stripe.js / Payment Element | CyberSource Flex Microform v2 |
| 3DS 处理 | `requires_action` + `handleNextAction()` | `PENDING_AUTHENTICATION` + Payer Authentication API |
| Java SDK Maven | `com.stripe:stripe-java` | `com.cybersource:cybersource-rest-client-java` |
| 沙箱地址 | `api.stripe.com`（同一域名不同 key） | `apitest.cybersource.com`（独立测试域名） |
| 生产地址 | `api.stripe.com` | `api.cybersource.com` |

---

## Step 0 — pom.xml 新增 CyberSource Maven 依赖

文件：`pom.xml`

```xml
<!-- CyberSource REST Client -->
<dependency>
    <groupId>com.cybersource</groupId>
    <artifactId>cybersource-rest-client-java</artifactId>
    <version>0.0.55</version>
</dependency>
```

> 注意：CyberSource SDK 内部用 OkHttp3 发请求，与现有 Hutool HttpClient 互不影响。

---

## Step 1 — 新增 PaymentChannel 枚举值

文件：`PaymentChannel.java`

```java
CYBERSOURCE("CYBERSOURCE", "CyberSource", "cybersource");
```

`supportsPaymentMethod` 支持：`VISA`、`MASTERCARD`、`AMEX`、`CARD`、`CREDIT_CARD`（泛指信用卡/借记卡）

---

## Step 2 — 实现 CyberSourceGatewayClient

文件：`CyberSourceGatewayClient.java`（继承 `AbstractPaymentGateway`）

### 2.1 认证机制（重点，必须覆盖基类方法）

`AbstractPaymentGateway.buildHeaders()` 目前生成 `X-API-Key` 和 `X-Signature`，**完全不适用于 CyberSource**。  
`CyberSourceGatewayClient` 必须 **`@Override buildHeaders()`**，生成 CyberSource HTTP Signature 认证头：

```
Authorization: Signature keyId="<keyId>",
               algorithm="HmacSHA256",
               headers="host date (request-target) digest v-c-merchant-id",
               signature="<base64_hmac>"
```

签名计算步骤（在子类实现）：
1. 拼装 signing string：`"host: api.cybersource.com\ndate: <Date>\n(request-target): post /pts/v2/payments\ndigest: SHA-256=<base64(sha256(body))>\nv-c-merchant-id: <merchantId>"`
2. `HMAC-SHA256(base64decode(sharedSecretKey), signingString)`
3. `base64encode(hmacResult)` 放入 Signature header

同时需要覆盖 `isAvailable()`，检查 `merchantId`、`keyId`、`sharedSecretKey` 三个配置均非空。

### 2.2 关键实现点

- `createPayment()` —— 调用 `POST /pts/v2/payments`
  - Request body 包含 `orderInformation`（金额/货币）、`clientReferenceInformation`（内部订单号）、`tokenInformation.transientTokenJwt`（Flex Microform 返回的前端 token）
  - 走 **Auth+Capture 合并模式**（`processingInformation.capture: true`），一步完成授权+扣款
  - Response 返回 `id`（即 CyberSource paymentId）存入 `GATEWAY_TRANSACTION_ID`
- `queryTransactionStatus()` —— 调用 `GET /tss/v2/transactions/{id}`，解析 `applicationInformation.status`
- `refund()` —— 调用 `POST /pts/v2/refunds`，body 中放 `paymentId`（原交易 ID）和 `orderInformation.amountDetails`（支持部分退款）
- `verifyCallback()` —— 取 header `v-c-signature`，用 sharedSecretKey 做 HMAC-SHA256 校验原始 body
- `parseCallbackData()` —— 解析 Webhook Event JSON，取 `payload[0].data.object`，映射状态
- `getPriority()` —— 返回 `10`（低于 SCB 的 `1`，SCB 专属页面通过 channel 强制指定，不走 priority）

### 2.3 CyberSource 状态映射

| CyberSource status | 系统状态 | 说明 |
|-------------------|---------|------|
| `AUTHORIZED` | `PENDING` | 仅授权，未 capture（本方案用 Auth+Capture 一步完成，此状态仅在查询短暂出现） |
| `AUTHORIZED_PENDING_REVIEW` | `PENDING` | 风控人工复核中 |
| `PENDING` | `PENDING` | 处理中 |
| `PENDING_AUTHENTICATION` | `PENDING` | 等待 3DS 验证 |
| `COMPLETED` | `SUCCESS` | 扣款成功 |
| `TRANSMITTED` | `SUCCESS` | 已提交清算 |
| `DECLINED` | `FAILED` | 发卡行拒绝 |
| `INVALID_REQUEST` | `FAILED` | 请求参数错误 |
| `VOIDED` | `FAILED` | 已撤销 |
| `CANCELLED` | `FAILED` | 已取消 |
| `REVERSED` | `REFUNDED` | 已冲正（退款） |

---

## Step 3 — 修改 PaymentGatewayRouter 路由规则

文件：`PaymentGatewayRouter.java`

修改 `selectGateway(String paymentMethod)` 逻辑：

```
if paymentMethod in [VISA, MASTERCARD, AMEX, CARD, CREDIT_CARD]
    → 路由到 CYBERSOURCE
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

## Step 4 — application.yml 新增 CyberSource 配置

```yaml
cybersource:
  merchant-id: ${CYBERSOURCE_MERCHANT_ID:}       # 商户ID（在 EBC 后台查看）
  key-id: ${CYBERSOURCE_KEY_ID:}                 # Shared Secret Key ID
  shared-secret-key: ${CYBERSOURCE_SECRET_KEY:}  # Shared Secret（Base64编码）
  api:
    endpoint: ${CYBERSOURCE_API_ENDPOINT:https://apitest.cybersource.com}  # 沙箱/生产切换
  webhook:
    secret: ${CYBERSOURCE_WEBHOOK_SECRET:}       # Webhook 签名秘钥（EBC 后台配置）
  currency: HKD
  run-environment: apitest.cybersource.com       # 沙箱: apitest / 生产: api.cybersource.com
```

> 三个必填：`merchant-id`、`key-id`、`shared-secret-key`，缺任一则 `isAvailable()` 返回 false，网关不启用。  
> 在 EBC（Enterprise Business Center）后台 → Key Management 生成 Shared Secret Key。

---

## Step 5 — 回调端点（复用现有通用路由）

现有通用回调路由 `POST /api/payment/callback/{channel}` 已通过 `PaymentChannel.fromCallbackPath("cybersource")` 自动路由，  
**不需要新增单独端点**，只需确保 Step 1 枚举 callbackPath 为 `"cybersource"` 即可。

回调处理细节：
- Header：`v-c-signature`（CyberSource Webhook 签名），`PaymentCallbackService` 从 headers map 中取
- Body：CyberSource Webhook JSON Event
- 事件类型：`payments.updated`（状态变更）、`payments.declined`
- 响应 `HTTP 200`（CyberSource 要求 200，否则会重试最多 15 次）

`PaymentCallbackService.processCallback()` 内获取 signature 的代码需从：
```java
String signature = headers.get("X-Signature");  // 旧代码
```
改为优先取 `v-c-signature`，降级取 `X-Signature`（保持 SCB 向后兼容）：
```java
String signature = headers.getOrDefault("v-c-signature", headers.get("X-Signature"));
```

---

## Step 6 — 前端区分渣打专属 vs 普通信用卡

### 普通信用卡支付页面（走 CyberSource）

**第一步：前端获取 Flex Microform captureContext**

```
GET /api/payment/cybersource/flex-key
← 后端调 POST /flex/v2/tokens/keys（CyberSource），返回 captureContext JWT
```

**第二步：前端用 Flex Microform v2 渲染卡号输入框**

```javascript
// 引入 CyberSource Flex Microform JS SDK（不是 Stripe.js！）
// https://flex.cybersource.com/microforms/bundle/v2/flex-microform.min.js

const flex = new Flex(captureContext);  // captureContext 从 /flex-key 接口获取
const microform = flex.microform();
const cardNumber = microform.createField('number');
cardNumber.load('#card-number-container');
// 用户输入卡号进入 CyberSource 沙箱，商户后端永远看不到真实卡号
```

**第三步：前端提交（带 transientToken）**

```json
POST /api/payment/initiate
{
  "orderReference": "CSP001234567",
  "amount": 176.00,
  "currency": "HKD",
  "paymentMethod": "VISA",
  "transientToken": "eyJraWQiOi..."
}
```

后端 `CyberSourceGatewayClient.createPayment()` 把 `transientToken` 放进 `tokenInformation.transientTokenJwt` 调 CyberSource。

### 渣打专属页面（走 SCB，不变）

```json
POST /api/payment/initiate
{
  "orderReference": "CSP001234567",
  "amount": 176.00,
  "currency": "HKD",
  "channel": "SCB"
}
```

强制指定 `channel=SCB`，走渣打原有 Hosted Checkout 流程，不影响现有逻辑。

---

## Step 7 — 退款兼容性确认

现有 `RefundApprovalService` / `RefundExecutionService` 通过 `PaymentGatewayRouter.getGateway(channel)` 获取网关执行退款。  
`PAYMENT_TRANSACTION` 表已有 `GATEWAY_NAME` 字段存储渠道代码（存 `"CYBERSOURCE"`）。  
退款时 `RefundExecutionService` 将 `gatewayTransactionId`（即 CyberSource paymentId）传给 `CyberSourceGatewayClient.refund()`，退款 API `POST /pts/v2/refunds` 在 body 中引用原始 paymentId。  
**退款审批流（财务审批 → 执行退款）无需改动。**

---

## Further Considerations

### 为什么用 CyberSource 而不是 Stripe？

| 对比项 | CyberSource | Stripe |
|--------|-------------|--------|
| 母公司 | Visa 旗下 | 独立公司 |
| HK 大型商户占比 | 非常高（银行/零售/航空） | 中小商户居多 |
| 企业级 SLA | 99.99%，有专属客户经理 | 标准 SLA |
| 风控能力 | Decision Manager（内置 AI 风控） | Radar（基础风控） |
| 开通方式 | 需商务合同 | 自助注册 |
| 认证复杂度 | 较高（HTTP Signature） | 低（Bearer Token） |
| 适用场景 | 大型企业，合规要求高 | 快速上线，中小规模 |

### PCI DSS 合规（重要）

必须使用 **CyberSource Flex Microform v2**，卡号由 CyberSource 直接接收，商户后端只处理 `transientToken`。  
若卡号经过商户后端，需通过 PCI DSS SAQ D 认证，年费 10 万美金+，审计周期 3~6 个月。  
Flex Microform = CyberSource 版的 Stripe.js Hosted Fields，原理完全相同，只是 SDK 不同。

### 3DS 2.0（CyberSource Payer Authentication）

CyberSource 3DS 流程比 Stripe 更复杂，分两步 API：
1. `POST /risk/v1/authentication-setups` —— 初始化 3DS 会话
2. `POST /risk/v1/authentications` —— 执行 3DS 验证，返回 `authenticationTransactionId`

状态 `PENDING_AUTHENTICATION` 时，前端需调 Cardinal Commerce JS（CyberSource 内置）完成挑战。  
通过 3DS 后，Chargeback 责任转移到发卡行，商户免责。

### AbstractPaymentGateway.buildHeaders() 必须覆盖（⚠️ 不覆盖必报 401）

现有基类 `buildHeaders()` 只生成 `X-API-Key` + `X-Signature`，CyberSource 完全不认这两个 Header。  
`CyberSourceGatewayClient` 必须 `@Override buildHeaders()` 生成完整 HTTP Signature Authorization Header，  
否则所有请求返回 `401 Unauthorized`。这是整个实现里最容易踩的坑。

### 测试凭证（CyberSource EBC Sandbox）

登录 `ebc2.cybersource.com` → Account Management → Key Management → Generate Key（Shared Secret）

### 测试卡号（CyberSource Sandbox）

| 卡号 | 类型 | 结果 |
|------|------|------|
| `4111111111111111` | Visa | 成功 |
| `5555555555554444` | Mastercard | 成功 |
| `378282246310005` | Amex | 成功 |
| `4000000000000002` | Visa | 拒绝（DECLINED） |
| `4000000000000101` | Visa | 需要 3DS 挑战 |
| `4242424242424242` | Visa | ❌ 这是 Stripe 测试卡，CyberSource 沙箱无效！ |

---

## 影响范围

| 组件 | 变更类型 | 备注 |
|------|---------|------|
| `pom.xml` | 新增依赖 | `cybersource-rest-client-java:0.0.55` |
| `PaymentChannel.java` | 新增枚举值 | 加 `CYBERSOURCE("CYBERSOURCE","CyberSource","cybersource")` |
| `CyberSourceGatewayClient.java` | 新增文件 | 继承 `AbstractPaymentGateway`，必须覆盖 `buildHeaders()` 和 `isAvailable()` |
| `PaymentGatewayRouter.java` | 修改路由逻辑 | VISA/MC/AMEX → CYBERSOURCE；新增 channelOverride 重载方法 |
| `PaymentCallbackService.java` | 微调 | signature header 优先取 `v-c-signature`，降级取 `X-Signature`（兼容 SCB） |
| `application.yml` | 新增配置 | `cybersource.*`（merchant-id / key-id / shared-secret-key） |
| `PaymentInitRequest.java` | 微调 | 新增 `transientToken` 字段（Flex Microform token）和 `channel` 覆盖字段 |
| `PaymentService.java` | 微调 | 透传 channelOverride 参数 |
| 渣打专属页面（前端） | 修改 | request 加 `"channel":"SCB"` |
| 普通信用卡页面（前端） | 修改 | 集成 CyberSource Flex Microform v2（替换 Stripe.js） |
| `RefundApprovalService.java` | **不需要改动** | 已通过 GATEWAY_NAME 路由 |
| `PAYMENT_TRANSACTION` 表 | **不需要改动** | `GATEWAY_NAME` 字段存 `"CYBERSOURCE"` 即可 |

---

## 原 Stripe 计划发现的问题清单（已全部修正）

| # | 问题 | 原计划（Stripe） | 修正后（CyberSource） |
|---|------|-----------------|----------------------|
| ① | 认证方式完全不同 | 沿用基类 `X-API-Key` Header | 覆盖 `buildHeaders()` 生成 HTTP Signature |
| ② | `isAvailable()` 检查项不足 | 只检查 endpoint + key | 检查 `merchantId` + `keyId` + `sharedSecretKey` 三个必填 |
| ③ | 缺少 Maven 依赖说明 | 无 Step 0 | 新增 Step 0，加 `cybersource-rest-client-java` |
| ④ | 前端 SDK 完全不同 | Stripe.js Payment Element | CyberSource Flex Microform v2（两步：获取 captureContext → 渲染） |
| ⑤ | 回调验签 Header 名不同 | `Stripe-Signature` | `v-c-signature` |
| ⑥ | 测试卡号不同 | `4242 4242 4242 4242`（Stripe 专用） | CyberSource 沙箱用 `4111111111111111` |
| ⑦ | Webhook 事件名格式不同 | `payment_intent.succeeded` | `payments.updated`（CyberSource 格式） |
| ⑧ | 退款 API 参数结构不同 | 未说明差异 | 原始 `paymentId` 放在 body，非 URL 参数 |
| ⑨ | 回调 signature 取值硬编码 | `headers.get("X-Signature")` | 优先取 `v-c-signature`，降级取 `X-Signature`（兼容 SCB） |

---

## 实现后发现的 Bug（v2 修复，2026-05-19）

CyberSource 集成上线后，在代码审查中发现 3 个已实现代码的缺陷，全部已修复。

### Bug A 🔴 — `PaymentCallbackServiceEnhanced` 签名 Header 取错（高危）

**文件**：`PaymentCallbackServiceEnhanced.java` 第 83 行

**问题代码**：
```java
// ❌ 错误：X-Signature 是 SCB 的 Header，CyberSource 根本不发这个
String signature = headers.get("X-Signature");
```

**修复代码**：
```java
// ✅ 优先取 CyberSource 的 v-c-signature，降级到 SCB 的 X-Signature（向后兼容）
String signature = headers.getOrDefault("v-c-signature", headers.get("X-Signature"));
```

**说明**：`PaymentCallbackController` 注入的是 `PaymentCallbackServiceEnhanced`（增强版，含 Redis 去重），  
而我们之前只修了基础版 `PaymentCallbackService`，改漏了增强版。  
这是实际生效的代码路径，不修必崩。

---

**不修复的危害时序图：**

```
CyberSource                 商户系统                      订单系统
     │                          │                              │
     │  POST /callback/cybersource                             │
     │  Header: v-c-signature: "abc123"                        │
     │ ──────────────────────────▶│                            │
     │                            │                            │
     │                            │ signature = headers.get("X-Signature")
     │                            │              ↑ 取到 null !!
     │                            │                            │
     │                            │ verifyCallback(body, null, headers)
     │                            │ → null != expectedSignature
     │                            │ → 签名验证失败 ❌           │
     │                            │                            │
     │                            │ callbackLog.status = "FAILED"
     │                            │                            │
     │   HTTP 200 "SUCCESS"       │                            │
     │ ◀──────────────────────────│                            │
     │                            │                            │
     │  (CyberSource 认为已收到，不重发)                        │
     │                            │                            │
     │  用户付款 100 元 ✅          │                            │
     │  PAYMENT_TRANSACTION       │                            │
     │  status 永远是 PENDING ❌   │ 订单永远是 UNPAID ❌        │
     │                            │ 货永远不发 ❌               │
     │                            │ 客服无法处理 ❌             │
```

**实际后果**：
- 用户付款成功，但订单状态永远停在 `PENDING`
- 财务对账：支付系统显示已收款，订单系统显示未付款，两边对不上
- 退款需要人工介入（已扣款但系统认为未付）
- **每一笔 CyberSource 支付都会出现此问题，影响 100% 的 CyberSource 订单**

---

### Bug B 🔴 — `PaymentService` 幂等键使用时间戳（高危）

**文件**：`PaymentService.java` `generateIdempotencyKey()` 方法

**问题代码**：
```java
// ❌ 时间戳每次不同 → 同一笔订单每次请求都有新 key → 幂等完全失效
private String generateIdempotencyKey(PaymentInitRequest request) {
    return request.getOrderReference() + "_" +
           request.getAmount().toString() + "_" +
           System.currentTimeMillis();  // ← 绝对不能用！
}
```

**修复代码**：
```java
// ✅ 三级策略，全部语义稳定，无时间戳
private String generateIdempotencyKey(PaymentInitRequest request) {
    // 策略1: 前端传 UUID（最推荐）
    if (notEmpty(request.getIdempotencyToken())) return request.getIdempotencyToken();
    // 策略2: 订单号 + 支付方式
    if (notEmpty(request.getPaymentMethod()))
        return request.getOrderReference() + "_" + request.getPaymentMethod();
    // 策略3: 订单号 + 渠道（channel override 场景）
    if (notEmpty(request.getChannel()))
        return request.getOrderReference() + "_" + request.getChannel();
    // 以上都没有 → 拒绝处理，防止重复扣款
    throw new PaymentException("请求缺少幂等标识");
}
```

**注意**：`PaymentController` 注入的是 `PaymentService`（非 Enhanced），所以这个 Bug 影响实际生效的支付入口。

---

**不修复的危害时序图：**

```
前端（用户）              商户系统                    CyberSource
    │                        │                             │
    │ POST /payment/initiate  │                             │
    │ { orderRef: "CSP001",   │                             │
    │   paymentMethod: "VISA" │                             │
    │   amount: 100 }         │                             │
    │ ───────────────────────▶│                             │
    │                         │ key = "CSP001_100_1748600001000"
    │                         │ SELECT → 不存在             │
    │                         │ INSERT PAYMENT_TXN ✅       │
    │                         │ POST /pts/v2/payments ─────▶│
    │  [网络超时，没有响应]    │◀─────────────── 200 ── CSId=cs001
    │                         │ UPDATE status=PENDING ✅    │
    │                         │                             │
    │  前端超时重试            │                             │
    │ POST /payment/initiate  │                             │
    │ { 同上 }                │                             │
    │ ───────────────────────▶│                             │
    │                         │ key = "CSP001_100_1748600002500"
    │                         │          ↑ 时间不同，新key!  │
    │                         │ SELECT → 不存在（新key查不到已有记录）
    │                         │ INSERT PAYMENT_TXN ✅（第二条！）
    │                         │ POST /pts/v2/payments ─────▶│
    │                         │◀─────────────── 200 ── CSId=cs002
    │                         │                             │
    │  用户被扣款两次！        │  两条 PAYMENT_TXN 记录      │
    │  损失 100 × 2 = 200 元  │  两笔 CyberSource 扣款      │
```

**实际后果**：
- 用户网络抖动重试时产生重复扣款，触发率约等于网络不稳定率（移动端尤其高）
- 法律风险：未经授权的双重扣款，违反 PCI DSS 规范
- 退款成本：每笔重复扣款需要人工退款 + CyberSource 退款手续费

---

### Bug C 🟡 — `buildSuccessResponse()` 缺少 CYBERSOURCE case（低危）

**文件**：`PaymentCallbackController.java`

**问题代码**：
```java
switch (channel) {
    case ALIPAY: return ResponseEntity.ok("success");
    case CCB:    return ResponseEntity.ok("OK");
    default:     return ResponseEntity.ok("SUCCESS");  // CYBERSOURCE 走这里，功能可用但不明确
}
```

**修复代码**：
```java
switch (channel) {
    case ALIPAY:       return ResponseEntity.ok("success");
    case CCB:          return ResponseEntity.ok("OK");
    case CYBERSOURCE:  return ResponseEntity.ok("SUCCESS");  // ✅ 显式处理
    default:           return ResponseEntity.ok("SUCCESS");
}
```

同时新增专属 endpoint `/cybersource`：
```java
@PostMapping("/cybersource")
public ResponseEntity<String> handleCyberSourceCallback(...) {
    return handleCallback("cybersource", rawBody, headers, request);
}
```

**危害**：功能上 default 也能正常工作（HTTP 200 CyberSource 接受），但：
- 代码意图不清晰，维护者容易误判
- 若将来某个渠道需要特殊响应格式，switch 会漏掉 CYBERSOURCE

---

## 完整时序图

### 时序图一：正常 CyberSource 支付流程

```
前端                  商户后端                  CyberSource              发卡行(HSBC等)
 │                       │                           │                          │
 │ ① GET /flex-key       │                           │                          │
 │ ──────────────────────▶│                           │                          │
 │                        │ POST /flex/v2/tokens/keys │                          │
 │                        │ ──────────────────────────▶                          │
 │                        │◀─────── captureContext JWT                           │
 │◀── captureContext ────│                           │                          │
 │                        │                           │                          │
 │ ② 渲染 Flex Microform  │                           │                          │
 │    用户输入卡号         │                           │                          │
 │    (卡号直接到CS服务器) │ ◀══════════════════════════ transientToken           │
 │    得到 transientToken │                           │                          │
 │                        │                           │                          │
 │ ③ POST /payment/initiate                           │                          │
 │ { paymentMethod:VISA,  │                           │                          │
 │   transientToken: xxx, │                           │                          │
 │   idempotencyToken: uuid}                          │                          │
 │ ──────────────────────▶│                           │                          │
 │                        │ selectGateway("VISA")     │                          │
 │                        │ → CyberSourceGatewayClient│                          │
 │                        │                           │                          │
 │                        │ Redis分布式锁              │                          │
 │                        │ 双重检查SELECT → 不存在    │                          │
 │                        │ INSERT PAYMENT_TXN(INIT)  │                          │
 │                        │                           │                          │
 │                        │ ④ POST /pts/v2/payments   │                          │
 │                        │ Authorization: Signature  │                          │
 │                        │ tokenInfo.transientTokenJwt                          │
 │                        │ processingInfo.capture:true                          │
 │                        │ ──────────────────────────▶                          │
 │                        │                           │ Flex token → 真实卡号    │
 │                        │                           │ ──────────────────────────▶
 │                        │                           │◀─── 授权成功 ─────────────
 │                        │ ◀── 200 { id: "cs001",   │                          │
 │                        │          status:COMPLETED}│                          │
 │                        │                           │                          │
 │                        │ UPDATE PAYMENT_TXN        │                          │
 │                        │ gatewayTxnId="cs001"      │                          │
 │                        │ status=PENDING            │                          │
 │                        │ 事务提交 → 释放锁          │                          │
 │                        │                           │                          │
 │◀── 200 { transactionId,│                           │                          │
 │         status:PENDING}│                           │                          │
```

---

### 时序图二：CyberSource Webhook 回调流程（含幂等保护）

```
CyberSource              商户后端                           DB / Redis
     │                       │                                   │
     │  ⑤ POST /callback/cybersource                            │
     │  Header: v-c-signature: "hmac-abc"                       │
     │  Body: { eventType:"payments.updated",                   │
     │          payload:[{ data:{ object:{                       │
     │            id:"cs001", status:"COMPLETED" }}}]}           │
     │ ──────────────────────▶│                                  │
     │                        │ 限流检查 (RateLimiter)           │
     │                        │ fromCallbackPath("cybersource")  │
     │                        │ → PaymentChannel.CYBERSOURCE     │
     │                        │ 异步处理（立即返回HTTP 200）      │
     │◀── HTTP 200 "SUCCESS" ─│                                  │
     │    (CyberSource 不会重发)                                  │
     │                        │                                  │
     │                        │ ── 异步线程开始 ──               │
     │                        │                                  │
     │                        │ 层1: 验签                        │
     │                        │ signature = headers              │
     │                        │   .getOrDefault("v-c-signature", │
     │                        │    headers.get("X-Signature"))   │
     │                        │      = "hmac-abc" ✅             │
     │                        │ verifyCallback(body, sig, headers)
     │                        │ HMAC-SHA256(webhookSecret, body) │
     │                        │ == "hmac-abc" → 验签通过 ✅      │
     │                        │                                  │
     │                        │ 层2: Redis去重                   │
     │                        │ key = "callback:processed:cs001:SUCCESS"
     │                        │ setIfAbsent → true (首次) ✅     │
     │                        │   ──────────────────────────────▶│
     │                        │                                  │ SET key=1 EX 86400
     │                        │                                  │
     │                        │ parseCallbackData():             │
     │                        │ cs_status="COMPLETED"            │
     │                        │ → mapCyberSourceStatus()         │
     │                        │ → "SUCCESS"                      │
     │                        │                                  │
     │                        │ SELECT PAYMENT_TXN               │
     │                        │ WHERE gatewayTxnId="cs001"       │
     │                        │   ──────────────────────────────▶│
     │                        │◀──── TXN(status=PENDING,ver=1) ──│
     │                        │                                  │
     │                        │ 层3: 终态检查                    │
     │                        │ PENDING ≠ 终态 → 继续 ✅         │
     │                        │                                  │
     │                        │ 乐观锁更新                       │
     │                        │ UPDATE PAYMENT_TXN               │
     │                        │ SET status="SUCCESS", ver=ver+1  │
     │                        │ WHERE id=? AND ver=1             │
     │                        │   ──────────────────────────────▶│
     │                        │◀──── 影响行数=1 ✅ ──────────────│
     │                        │                                  │
     │                        │ notifyOrderSystem() [TODO: MQ]   │
     │                        │                                  │
     │                        │ 写 PAYMENT_CALLBACK_LOG ✅       │
     │                        │   ──────────────────────────────▶│
     │                        │                                  │
     │  (若 CyberSource 重发)  │                                  │
     │ ──────────────────────▶│                                  │
     │                        │ Redis去重: key已存在             │
     │                        │ → DUPLICATE_REDIS，丢弃 ✅       │
     │◀── HTTP 200 "SUCCESS" ─│                                  │
```

---

### 时序图三：幂等防重复扣款（Redis 分布式锁）

```
线程A（用户首次点击）          线程B（用户重复点击/网络重试）     Redis      DB
       │                               │                         │          │
       │ initiatePayment(req)          │                         │          │
       │ idempotencyKey = "CSP001_VISA"│                         │          │
       │                               │                         │          │
       │ lock.tryLock("CSP001_VISA")──────────────────────────▶  │          │
       │◀─── 获锁成功 ─────────────────────────────────────────  │          │
       │                               │                         │          │
       │  BEGIN TRANSACTION            │                         │          │
       │  SELECT WHERE key="CSP001_VISA" ──────────────────────────────────▶│
       │◀─────────────────────────────────────── 不存在 ─────────────────── │
       │  INSERT PAYMENT_TXN ──────────────────────────────────────────────▶│
       │  POST /pts/v2/payments → CyberSource ✅                 │          │
       │  UPDATE status=PENDING ───────────────────────────────────────────▶│
       │  COMMIT ✅（事务此刻提交）    │                         │          │
       │                               │ lock.tryLock("CSP001_VISA")        │
       │                               │ ───────────────────────▶│          │
       │                               │◀────── 等待中（A还持锁）│          │
       │ lock.unlock() ────────────────────────────────────────▶ │          │
       │                               │◀────── 获锁成功 ────────│          │
       │                               │                         │          │
       │                               │ 双重检查                │          │
       │                               │ SELECT WHERE key="CSP001_VISA" ───▶│
       │                               │◀────────── 已存在！(A刚提交) ──────│
       │                               │                         │          │
       │                               │ 直接返回原交易结果 ✅    │          │
       │                               │ 不调 CyberSource ✅     │          │
       │                               │ 不重复扣款 ✅           │          │
```

---

### 时序图四：CyberSource 退款流程（财务审批 → 执行）

```
财务人员              商户后台              商户后端                  CyberSource
    │                    │                     │                           │
    │ 登录财务后台        │                     │                           │
    │ (JWT含parentGroupId=345)                 │                           │
    │ ──────────────────▶│                     │                           │
    │                    │                     │                           │
    │ 查看退款申请列表    │                     │                           │
    │ GET /finance/refund/list                 │                           │
    │ ──────────────────▶│ ──────────────────▶ │                           │
    │◀── PENDING_REVIEW申请列表 ───────────────│                           │
    │                    │                     │                           │
    │ 审批通过            │                     │                           │
    │ POST /finance/refund/approve             │                           │
    │ ──────────────────▶│ ──────────────────▶ │                           │
    │                    │                     │ 验证财务角色               │
    │                    │                     │ parentGroupId==345 ✅      │
    │                    │                     │ 写 REFUND_AUDIT_LOG ✅     │
    │◀── 审批成功 ────── │                     │                           │
    │                    │                     │                           │
    │                    │   [异步执行退款]      │                           │
    │                    │                     │ 状态 REFUNDING（乐观锁抢占）
    │                    │                     │                           │
    │                    │                     │ ① POST /pts/v2/refunds    │
    │                    │                     │ { paymentInformation:     │
    │                    │                     │     captureId: "cs001",   │
    │                    │                     │   orderInfo:              │
    │                    │                     │     totalAmount: "50.00"} │
    │                    │                     │ ─────────────────────────▶│
    │                    │                     │ Authorization: Signature  │
    │                    │                     │◀──── 200 { id:"rf001",    │
    │                    │                     │      status:"PENDING" }   │
    │                    │                     │                           │
    │ 刷新页面看结果      │                     │ 状态 PARTIALLY_REFUNDED   │
    │ ──────────────────▶│                     │（部分退款，未超过原金额）  │
    │◀── COMPLETED ─────│                     │ 或 REFUNDED（全额退款）   │
```

---

## 完整影响范围（含 v2 Bug 修复）

| 组件 | 变更类型 | 状态 | 备注 |
|------|---------|------|------|
| `pom.xml` | 新增依赖 | ✅ 已完成 | `cybersource-rest-client-java:0.0.55` |
| `PaymentChannel.java` | 新增枚举值 | ✅ 已完成 | 加 `CYBERSOURCE("CYBERSOURCE","CyberSource","cybersource")` |
| `CyberSourceGatewayClient.java` | 新增文件 | ✅ 已完成 | 530行，含 HTTP Signature / 幂等 / 退款 / 回调验签 |
| `StandardCharteredGatewayClient.java` | 修改 | ✅ 已完成 | `supportsPaymentMethod()` 改返回 false，SCB 不参与自动路由 |
| `PaymentGatewayRouter.java` | 修改路由逻辑 | ✅ 已完成 | VISA/MC/AMEX → CYBERSOURCE；新增 `selectGateway(method, channelOverride)` |
| `PaymentCallbackServiceEnhanced.java` | **Bug A 修复** | ✅ 已修复 | signature 改 `getOrDefault("v-c-signature","X-Signature")`（高危修复） |
| `PaymentCallbackService.java` | 微调 | ✅ 已完成 | 同 Bug A，基础版同步修复 |
| `PaymentService.java` | **Bug B 修复** | ✅ 已修复 | `generateIdempotencyKey()` 去掉时间戳，改三级稳定幂等策略（高危修复） |
| `PaymentServiceEnhanced.java` | 微调 | ✅ 已完成 | `initiatePayment()` 支持 `request.getChannel()` 覆盖 |
| `PaymentCallbackController.java` | **Bug C 修复** + 新增端点 | ✅ 已完成 | 加 CYBERSOURCE case；新增 `POST /callback/cybersource` 专属端点 |
| `application.yml` | 新增配置 | ✅ 已完成 | `cybersource.*`（merchant-id / key-id / shared-secret-key） |
| `PaymentInitRequest.java` | 微调 | ✅ 已完成 | 新增 `transientToken` 和 `channel` 字段 |
| 渣打专属页面（前端） | 需修改 | ⏳ 待前端处理 | request 加 `"channel":"SCB"` |
| 普通信用卡页面（前端） | 需实现 | ⏳ 待前端处理 | 集成 CyberSource Flex Microform v2 |
| `RefundApprovalService.java` | **不需要改动** | ✅ 已验证 | 退款流程通过 `GATEWAY_NAME="CYBERSOURCE"` 自动路由 |
| `PAYMENT_TRANSACTION` 表 | **不需要改动** | ✅ 已验证 | 现有字段已满足 CyberSource 存储需求 |

