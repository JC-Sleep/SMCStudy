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
