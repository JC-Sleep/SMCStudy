# 银行API对接完全指南
> 渣打银行 / 汇丰银行 / 中国建设银行 / 支付宝
> 从零开始教你怎么对接，SDK是什么，文档是什么，为什么给你这些东西

---

## 目录

1. [为什么银行要给你文档和SDK？](#为什么银行要给你文档和sdk)
2. [对接银行的完整流程（通用步骤）](#对接银行的完整流程通用步骤)
3. [渣打银行（Standard Chartered）对接详解](#渣打银行standard-chartered)
4. [汇丰银行（HSBC）对接详解](#汇丰银行hsbc)
5. [中国建设银行（CCB）对接详解](#中国建设银行ccb)
6. [支付宝（Alipay）对接详解](#支付宝alipay)
7. [四大渠道横向对比](#四大渠道横向对比)
8. [常见错误与排查](#常见错误与排查)

---

## 为什么银行要给你文档和SDK？

### 大白话解释

> 想象你要去银行柜台，但柜台不对外开放，只能通过"对讲机"联系。
> 银行给你的文档，就是"对讲机操作手册"；
> SDK就是"银行帮你预装好的对讲机App，你按按钮就能说话"。

### 技术层面解释

银行的核心系统（Core Banking System）是高度封闭的，不对外直接暴露。

```
你的商户系统
      ↓
银行对外API网关（Gateway）← 这是银行专门开放给商户的"窗口"
      ↓
银行内部核心系统（你看不到，也碰不到）
```

**文档（API Document）** 是什么：
```
告诉你：
  ├── 服务器地址是什么（endpoint）：https://api.sc.com/payment/v1
  ├── 请求格式是什么（JSON? XML?）
  ├── 必须传哪些字段（amount, currency, merchantId...）
  ├── 怎么加密/签名（防止伪造请求）
  ├── 银行会返回什么（status codes, error codes）
  └── 回调通知格式（银行扣款成功后怎么告诉你）
```

**SDK（Software Development Kit）** 是什么：
```
银行帮你写好的代码库，封装了：
  ├── HTTP请求发送
  ├── 签名/加密逻辑（最复杂的部分！）
  ├── 请求参数验证
  ├── 响应解析
  └── 错误处理

你不用SDK：自己写签名算法（RSA、HMAC-SHA256...），容易出错
你用SDK：  调用 sdk.createPayment(request) 一行搞定
```

**为什么银行要给你这些**：

| 原因 | 说明 |
|------|------|
| 保护自己 | 如果商户随便"猜"接口，可能发出错误指令，银行出了问题 |
| 标准化 | 全世界几千个商户都来对接，格式统一才好管理 |
| 安全合规 | 签名/加密必须按银行要求做，否则拒绝服务 |
| 商业关系 | 只有签了合同的商户才能拿到文档和SDK |

---

## 对接银行的完整流程（通用步骤）

任何银行对接，步骤都大同小异：

```
Step 1：商务签约
  ├── 去银行开立商户账户（Merchant Account）
  ├── 签署支付服务协议
  └── 提供营业执照、网站域名、回调地址等资料

Step 2：拿到凭证
  ├── merchantId（商户号）：你在银行的唯一编号
  ├── apiKey / secretKey：接口认证密钥
  ├── 证书文件（.pem / .p12）：双向TLS或报文签名用
  └── 沙箱环境（Sandbox）账号：测试用，扣的是假钱

Step 3：阅读文档 + 下载SDK
  ├── 看文档了解接口格式
  └── 下载SDK（Java/Python/PHP/...）

Step 4：沙箱（UAT）联调
  ├── 用沙箱凭证发测试请求
  ├── 模拟各种场景：成功、失败、超时、重复
  └── 确认回调地址能被银行访问（需要公网IP或内网穿透）

Step 5：生产上线
  ├── 换成生产环境凭证（Prod merchantId + apiKey）
  ├── 正式上线，监控第一批交易
  └── 保留沙箱环境供日后回归测试
```

---

## 渣打银行（Standard Chartered）

### 1. 在哪里申请

**官方开发者门户**：
```
https://developer.sc.com/
```

对于香港商户（如本系统 CSP Sales Portal）：
```
渠道：Standard Chartered Business Banking
申请路径：
  企业网银登录 → 增值服务 → 支付网关（Payment Gateway）→ 申请商户号
  或联系SCB企业客户经理
```

### 2. 拿到的凭证（渣打）

```
申请成功后，银行邮件/门户给你：

├── merchantId          = "SCB_MERCHANT_HK_001234"
├── apiKey              = "sk_prod_xxxxxxxxxxxxxxxxxxxxxxxx"
├── privateKey.pem      = 你的私钥（请求签名用）
├── scb_public_cert.pem = 渣打公钥（验证回调签名用）
├── UAT Endpoint        = "https://uat-api.sc.com/payment/v1"
└── PROD Endpoint       = "https://api.sc.com/payment/v1"
```

### 3. 没有官方Java SDK怎么办？

渣打没有开源SDK（大部分欧美银行如此），你需要**自己写HTTP客户端**。
这就是为什么本系统有自己的 `PaymentGateway` 接口实现。

```java
// 核心：签名生成（HMAC-SHA256 最常见）
String payload = merchantId + ":" + timestamp + ":" + requestBody;
String signature = HmacSHA256(payload, secretKey);

// 请求头
headers.add("Authorization", "Bearer " + apiKey);
headers.add("X-Signature", signature);
headers.add("X-Timestamp", timestamp);
headers.add("X-MerchantId", merchantId);

// 发起请求
POST https://api.sc.com/payment/v1/initiate
Content-Type: application/json

{
  "merchantTransactionId": "TXN8823761234",
  "amount": 100.00,
  "currency": "HKD",
  "paymentMethod": "CARD",
  "returnUrl": "https://your-app.com/payment/return",
  "callbackUrl": "https://your-app.com/api/payment/callback"
}
```

### 4. 渣打支付完整时序图

```
商户系统(你)              渣打API网关               持卡人
     |                        |                        |
     |                        |                        |
  [Step1: 建单]               |                        |
     |-- POST /payment/initiate                        |
     |   {merchantTxnId, amount, currency}             |
     |   Header: Authorization, X-Signature ---------> |
     |                        |-- 验证签名             |
     |                        |-- 创建订单入库         |
     |<-- 200 OK -------------|                        |
     |   {                    |                        |
     |     scbTxnId: "SCB001",|                        |
     |     paymentUrl: "https://pay.sc.com/xxx",       |
     |     status: "PENDING", |                        |
     |     expiresAt: "..."   |                        |
     |   }                    |                        |
     |                        |                        |
  [Step2: 用户支付]           |                        |
     |-- 把paymentUrl发给持卡人 --------------------> |
     |                        |                        |
     |                        |<-- 持卡人填写卡号/3DS--|
     |                        |-- 向发卡行授权         |
     |                        |<-- 授权成功            |
     |                        |-- 扣款                 |
     |                        |                        |
  [Step3: 收到回调]           |                        |
     |<-- POST /api/payment/callback                   |
     |   {                    |                        |
     |     scbTxnId: "SCB001",|                        |
     |     merchantTxnId: "TXN8823761234",             |
     |     status: "SUCCESS", |                        |
     |     signature: "xxxx"  |-- 验证签名              |
     |   }                    |                        |
     |-- 验证渣打回调签名      |                        |
     |-- 更新DB状态为SUCCESS   |                        |
     |-- 返回200告知已收到     |                        |
     |-- 通知订单系统发货      |                        |
```

### 5. 回调签名验证（关键！防伪造）

```java
// 渣打回调时，header里有签名
String receivedSignature = request.getHeader("X-Signature");
String callbackBody = request.getBody();
String timestamp = request.getHeader("X-Timestamp");

// 用渣打给你的公钥验证
// (渣打用他们的私钥签名，你用他们的公钥验证)
boolean isValid = verifyRSASignature(
    callbackBody + timestamp,     // 被签名的内容
    receivedSignature,            // 渣打的签名
    scbPublicKey                  // 渣打的公钥（他们给你的 scb_public_cert.pem）
);

if (!isValid) {
    // 可能是伪造回调！拒绝处理
    return 401;
}
```

**为什么要验证签名**：
```
如果不验证签名，任何人都可以伪造回调：

黑客 → POST /api/payment/callback
       { scbTxnId: "SCB001", status: "SUCCESS" }
→ 你的系统以为付款成功
→ 发货了！
→ 实际根本没有扣款
```

---

## 汇丰银行（HSBC）

### 1. 在哪里申请

**香港商户（PayMe for Business / HSBC Payment Gateway）**：
```
https://www.hsbc.com.hk/business/payments/payme-for-business/
或
https://developer.hsbc.com/
```

### 2. 拿到的凭证（汇丰）

```
├── merchantCode        = "HSBC_HK_MERCHANT_5678"
├── accessToken         = "eyJhbGciOiJSUzI1NiJ9..."  (JWT格式)
├── clientId            = "client_abc123"
├── clientSecret        = "secret_xyz789"
├── keystore.p12        = 客户端证书（双向TLS）
├── UAT URL             = "https://sandbox.hsbc.com.hk/payme/api/v1"
└── PROD URL            = "https://api.hsbc.com.hk/payme/api/v1"
```

### 3. 汇丰的特殊之处：OAuth 2.0 认证

汇丰比渣打多一步：需要先换 Access Token，再用 Token 调用业务接口。

```
Step 1：换Token（每2小时换一次）
POST https://api.hsbc.com.hk/oauth/token
Body: grant_type=client_credentials
      &client_id=client_abc123
      &client_secret=secret_xyz789
Response: { "access_token": "eyJhbGci...", "expires_in": 7200 }

Step 2：用Token调用业务接口
POST https://api.hsbc.com.hk/payme/api/v1/payment/create
Header: Authorization: Bearer eyJhbGci...
Body: { "orderId": "ORD001", "amount": 100, "currency": "HKD" }
```

### 4. 汇丰 PayMe 支付时序图

```
商户系统           汇丰OAuth服务器      汇丰支付网关         客户手机
    |                    |                   |                  |
    |-- POST /oauth/token                    |                  |
    |   client_credentials  ---------------->|                  |
    |<-- { access_token } ------------------|                  |
    |                    |                   |                  |
    |-- POST /payment/create                 |                  |
    |   Header: Bearer <token>               |                  |
    |   Body: {orderId, amount} ------------>|                  |
    |<-- { hsbcRef, qrCodeUrl } ------------|                  |
    |                    |                   |                  |
    |-- 显示QR码给客户 ---------------------------------------->|
    |                    |                   |                  |
    |                    |<-- 客户扫码+PayMe授权 --------------|
    |                    |                   |-- 扣款          |
    |                    |                   |                  |
    |<-- POST /callback {hsbcRef, SUCCESS} --|                  |
    |-- 更新订单状态      |                   |                  |
```

### 5. HSBC SDK（有官方SDK！）

```xml
<!-- Maven dependency -->
<dependency>
    <groupId>com.hsbc</groupId>
    <artifactId>hsbc-payments-sdk</artifactId>
    <version>2.3.1</version>
</dependency>
```

```java
// 使用SDK（比渣打简单很多）
HSBCPaymentClient client = new HSBCPaymentClient.Builder()
    .clientId("client_abc123")
    .clientSecret("secret_xyz789")
    .environment(Environment.PRODUCTION)
    .build();

CreatePaymentRequest req = CreatePaymentRequest.builder()
    .orderId("ORD001")
    .amount(new BigDecimal("100.00"))
    .currency("HKD")
    .callbackUrl("https://your-app.com/callback")
    .build();

CreatePaymentResponse resp = client.createPayment(req);
// resp.getQrCodeUrl() → 给用户扫码
```

---

## 中国建设银行（CCB）

### 1. 在哪里申请

**企业商户（龙商汇/CCB龙支付）**：
```
https://ebank.ccb.com/corporbank/  → 企业网银
或
http://www.ccbpay.com/             → 龙支付商户平台
```

**注意**：建行对接需要线下去网点，手续比渣打/汇丰繁琐。

### 2. 建行的特殊性：XML格式 + 证书双向认证

建行是传统银行，接口格式较老，很多接口还在用 **XML** 而不是JSON：

```xml
<!-- 建行请求格式示例（很多接口还是XML！） -->
<?xml version="1.0" encoding="UTF-8"?>
<REQUEST>
    <MERID>CCB_MERCHANT_001</MERID>
    <ORDERID>ORD20260416001</ORDERID>
    <AMOUNT>10000</AMOUNT>       <!-- 单位：分！100元 = 10000 -->
    <CURCD>01</CURCD>            <!-- 01=人民币 -->
    <SIGN>BASE64_SIGNATURE_HERE</SIGN>
</REQUEST>
```

**注意建行特殊规则**：
- 金额单位是"分"（100元 = 10000）
- 货币用代码（01=人民币, 06=港币）
- 签名用 MD5 或 RSA（老接口用MD5，新接口用RSA）

### 3. 建行支付时序图（网关支付）

```
商户系统（你）          建行支付网关              客户浏览器/App
    |                      |                          |
    |-- POST /gateway/pay  |                          |
    |   {merid, orderid, amount, sign}                |
    |   (XML格式) -------->|                          |
    |<-- { orderNo, payUrl } (XML响应)                |
    |                      |                          |
    |-- 重定向客户到payUrl --------------------------> |
    |                      |<-- 客户登录建行网银选择扣款|
    |                      |-- 验证+扣款              |
    |<-- POST /callback    |                          |
    |   {orderid, status, sign}                       |
    |   (建行回调，XML或JSON) ----------------------- |
    |-- 验证MD5/RSA签名    |                          |
    |-- 更新订单状态        |                          |
```

### 4. 建行SDK（官方提供Java包）

```java
// 建行官方提供的SDK（通常是jar包，不在Maven中央仓库）
// 需要从建行开发者平台下载，手动引入

// 或者自己实现（建行文档有示例）
CCBPayRequest request = new CCBPayRequest();
request.setMerid("CCB_MERCHANT_001");     // 商户号
request.setOrderId("ORD20260416001");    // 订单号
request.setAmount("10000");               // 金额（分）
request.setCurcd("01");                   // 货币（01=人民币）
request.setSign(generateSign(request));   // 签名

String xmlBody = request.toXml();
String response = httpClient.post(CCB_UAT_URL, xmlBody);
CCBPayResponse resp = CCBPayResponse.fromXml(response);
```

### 5. 建行对接注意事项

```
⚠️  建行特别需要注意：

1. 测试环境需要申请（不是自助注册）
   → 需要联系建行企业客户经理开通沙箱

2. IP白名单
   → 建行要求你提供服务器公网IP
   → 非白名单IP的请求直接拒绝（403）
   → 开发时需要用公网IP或向建行申请临时IP

3. 证书安装
   → 建行提供 .pfx/.p12 证书文件
   → 需要安装到JVM密钥库（keystore）
   keytool -importkeystore -srckeystore ccb.p12 
           -srcstoretype PKCS12 
           -destkeystore keystore.jks

4. 同一订单号不能重复提交
   → 只要 orderNo 存在（无论什么状态），再提交会报错
   → 与渣打/汇丰不同（他们是幂等的）
```

---

## 支付宝（Alipay）

### 1. 在哪里申请

**最简单，全自助注册！**

```
蚂蚁开放平台：https://open.alipay.com/
步骤：
  1. 注册支付宝账号 → 开发者认证
  2. 创建应用（获得 appId）
  3. 配置密钥（自己生成RSA2密钥对）
  4. 申请"当面付"或"APP支付"能力
  5. 沙箱环境立即可用！不需要等审核
```

### 2. 拿到的凭证（支付宝）

```
支付宝对接凭证（全部在开放平台自助生成）：

├── appId               = "2021000000000001"    // 你的应用ID
├── merchantPrivateKey  = RSA2私钥（你自己生成）
├── alipayPublicKey     = 支付宝公钥（从平台复制）
├── notifyUrl           = "https://your-app.com/alipay/callback"
├── sandbox API         = "https://openapi.alipaydev.com/gateway.do"
└── prod API            = "https://openapi.alipay.com/gateway.do"
```

**支付宝密钥自助生成（无需申请）**：
```bash
# 支付宝提供工具生成 RSA2 密钥对
# 工具地址：https://opendocs.alipay.com/common/02kipv

# 生成后：
# 1. 私钥（自己保存）：merchantPrivateKey = "MIIEvgIBADAN..."
# 2. 公钥（上传到支付宝平台）：上传后平台给你 alipayPublicKey
```

### 3. 支付宝有官方SDK！而且是最完善的

```xml
<!-- Maven dependency -->
<dependency>
    <groupId>com.alipay.sdk</groupId>
    <artifactId>alipay-sdk-java</artifactId>
    <version>4.38.0.ALL</version>
</dependency>
```

```java
// 初始化客户端（一次性配置）
AlipayClient alipayClient = new DefaultAlipayClient(
    "https://openapi.alipay.com/gateway.do",  // 网关地址
    "2021000000000001",                        // appId
    merchantPrivateKey,                        // 你的RSA2私钥
    "json",                                    // 数据格式
    "UTF-8",                                   // 编码
    alipayPublicKey,                           // 支付宝公钥
    "RSA2"                                     // 签名算法
);

// 当面付（生成收款QR码）
AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
request.setNotifyUrl("https://your-app.com/alipay/callback");
request.setBizContent(JSON.toJSONString(Map.of(
    "out_trade_no", "ORD20260416001",   // 商户订单号（唯一）
    "total_amount", "100.00",           // 金额（元，不是分！）
    "subject", "CSP销售-订单001",       // 商品描述
    "timeout_express", "5m"             // 超时时间（5分钟后二维码失效）
)));

AlipayTradePrecreateResponse response = alipayClient.execute(request);
String qrCode = response.getQrCode(); // 二维码内容，前端生成图片展示
```

### 4. 支付宝当面付完整时序图

```
商户系统（你）          支付宝平台              客户手机（支付宝App）
    |                      |                           |
    |-- alipay.trade.precreate                         |
    |   {out_trade_no, amount, subject} ---------->    |
    |<-- { qr_code: "https://qr.alipay.com/xxx" } --  |
    |                      |                           |
    |-- 生成QR码图片给销售员手机显示                     |
    |                      |                           |
    |                      |<-- 客户扫码（支付宝App）-- |
    |                      |-- 扣款（支付宝余额/绑定卡）|
    |                      |                           |
    |<-- POST /alipay/callback                         |
    |   {out_trade_no, trade_no, trade_status: SUCCESS}|
    |-- 验证支付宝签名      |                           |
    |-- 验证金额是否一致    |  ← 必须做！防止篡改金额    |
    |-- 更新订单状态        |                           |
    |-- return "success"   |  ← 必须返回这个字符串！    |
    |                      |   否则支付宝以为你没收到，  |
    |                      |   会一直重发（最多24小时）  |
```

### 5. 支付宝回调验证（与银行不同，要验证金额！）

```java
@PostMapping("/alipay/callback")
public String alipayCallback(HttpServletRequest request) {
    Map<String, String> params = getParamsFromRequest(request);
    
    // Step1：验证支付宝签名
    boolean signVerified = AlipaySignature.rsaCheckV1(
        params,
        alipayPublicKey,  // 支付宝公钥
        "UTF-8",
        "RSA2"
    );
    if (!signVerified) {
        log.error("支付宝回调签名验证失败！可能是伪造请求！");
        return "fail"; // 不要返回success！
    }
    
    // Step2：验证金额（防止黑客篡改金额为1分钱）
    String tradeStatus = params.get("trade_status");
    BigDecimal notifyAmount = new BigDecimal(params.get("total_amount"));
    BigDecimal orderAmount = getOrderAmount(params.get("out_trade_no")); // 查DB
    if (notifyAmount.compareTo(orderAmount) != 0) {
        log.error("金额不匹配！通知金额：{}，订单金额：{}", notifyAmount, orderAmount);
        return "fail";
    }
    
    // Step3：处理业务逻辑
    if ("TRADE_SUCCESS".equals(tradeStatus)) {
        updateOrderSuccess(params.get("out_trade_no"));
    }
    
    return "success"; // ← 必须返回这个，否则支付宝重发24小时！
}
```

### 6. 支付宝关键接口一览

| 接口名 | 作用 | 对应场景 |
|--------|------|---------|
| `alipay.trade.precreate` | 生成收款二维码 | 当面付（商户出示QR） |
| `alipay.trade.pay` | 主动扣款（用户出示付款码） | 当面付（用户出示条形码） |
| `alipay.trade.app.pay` | APP支付 | 手机APP内调用支付宝 |
| `alipay.trade.wap.pay` | 手机网页支付 | H5页面调起支付宝 |
| `alipay.trade.query` | 查询交易状态 | 超时后查询 |
| `alipay.trade.close` | 关闭交易 | 超时未付，主动关闭 |
| `alipay.trade.refund` | 退款 | 退款 |

---

## 四大渠道横向对比

### 对接难度对比

```
难度排名（从易到难）：

1. 支付宝    ⭐⭐         → 文档最好，SDK最完善，沙箱随用随开，新手友好
2. 汇丰HSBC  ⭐⭐⭐        → 有官方SDK，OAuth认证稍复杂，文档齐全
3. 渣打SCB   ⭐⭐⭐⭐       → 无官方SDK，需自写HTTP客户端，签名较复杂
4. 建行CCB   ⭐⭐⭐⭐⭐      → XML格式、证书安装、IP白名单、手续繁琐
```

### 对接信息汇总表

| 维度 | 渣打SCB | 汇丰HSBC | 建行CCB | 支付宝 |
|------|---------|---------|---------|--------|
| 注册方式 | 线下签约 | 线下签约 | 线下签约+网点 | **全自助在线** |
| 请求格式 | JSON | JSON | **XML**（老接口）| JSON |
| 认证方式 | HMAC签名 | OAuth 2.0 | RSA/MD5证书 | RSA2签名 |
| 官方SDK | ❌ 无 | ✅ 有 | ✅ 有（需手动下载）| ✅ 有（Maven）|
| 沙箱环境 | 需申请 | 需申请 | 需申请 | **自助立即开通** |
| IP白名单 | 否 | 否 | **是（必须）** | 否 |
| 金额单位 | 元（小数）| 元（小数）| **分（整数）** | 元（小数）|
| 回调重试 | 3次（1分钟内）| 3次 | 3次 | **最多24小时** |
| 返回体格式 | JSON | JSON | XML | JSON |
| 适用货币 | HKD/USD/多币种 | HKD/多币种 | CNY为主 | CNY为主 |

### 签名算法对比

```
渣打SCB：
  HMAC-SHA256(merchantId + ":" + timestamp + ":" + body, secretKey)
  → 对称加密，密钥是共享密钥

汇丰HSBC：
  JWT Bearer Token（先换Token，Token里含签名）
  → OAuth 2.0，定期刷新

建行CCB：
  RSA256(requestXml, merchantPrivateKey) → Base64编码
  老接口：MD5(params + secretKey) → 32位大写十六进制
  → 非对称加密，你签名，银行用你的公钥验

支付宝：
  RSA2/SHA256withRSA(params排序后字符串, merchantPrivateKey)
  → 非对称加密，最标准的实现
```

---

## 常见错误与排查

### 错误1：签名验证失败（Sign Error）

```
错误信息：
  渣打：{ "error": "INVALID_SIGNATURE" }
  支付宝：{ "sub_msg": "验签失败" }

原因排查：
  ├── 时间戳偏差超过5分钟（服务器时间不同步）
  ├── 签名内容与文档不符（多空格、少字段、顺序错）
  ├── 密钥搞错了（用了UAT密钥请求PROD）
  └── Base64编码方式错误（要用标准Base64，不是URL安全Base64）

排查方法：
  1. 把你发出的原始报文打印出来
  2. 按文档重新手动计算签名
  3. 对比你代码算出的签名
```

### 错误2：回调收不到

```
原因排查：
  ├── callbackUrl 是内网地址（银行无法访问）
  │     → 开发时用 ngrok 内网穿透：ngrok http 8080
  ├── 防火墙/安全组未开放80/443端口
  ├── SSL证书问题（回调URL必须是HTTPS）
  └── 建行：服务器IP不在白名单
  
验证方法：
  curl -X POST https://your-app.com/callback 
       -H "Content-Type: application/json" 
       -d '{"test": true}'
  （从外网测试能否访问你的回调地址）
```

### 错误3：重复订单号

```
错误信息：
  建行："该订单号已存在"
  支付宝："TRADE_HAS_SUCCESS" 或 "OUT_TRADE_NO_EXIST"
  
原因：同一个 out_trade_no / orderNo 提交了两次

处理方法：
  ├── 先查询订单状态（查询接口）
  ├── 如果已SUCCESS → 不用再提交，直接更新本地状态
  ├── 如果PENDING → 等待或重推付款链接
  └── 如果CLOSED  → 关闭后可以用同一订单号重新提交（支付宝支持）
                    建行不支持，需要新订单号
```

### 错误4：金额不匹配

```
⚠️ 建行的金额是"分"，100元要传 10000
   支付宝/渣打/汇丰是"元"，100元传 100.00

代码里容易犯的错：
  // 错误！建行会扣 0.01 元（传了1而不是100）
  request.setAmount(String.valueOf(orderAmount)); // orderAmount=1.00元 → "1"

  // 正确！建行单位是分
  long amountInFen = orderAmount.multiply(BigDecimal.valueOf(100)).longValue();
  request.setAmount(String.valueOf(amountInFen)); // → "100"
```

### 错误5：沙箱 vs 生产配置混淆

```java
// 建议在配置文件里明确区分：

# application-prod.yml
scb.endpoint=https://api.sc.com/payment/v1
scb.merchant-id=SCB_PROD_MERCHANT_001
scb.api-key=sk_prod_xxxxx  # 生产密钥

# application-uat.yml
scb.endpoint=https://uat-api.sc.com/payment/v1
scb.merchant-id=SCB_UAT_MERCHANT_001
scb.api-key=sk_uat_yyyyy   # 沙箱密钥

// 启动时必须确认当前环境：
log.info("当前支付环境：{}，端点：{}", activeProfile, endpoint);
// 绝对不能在生产环境用沙箱密钥，也不能在测试时用生产密钥！！
```

---

## 总结：对接银行的本质

```
所谓"对接银行API"，本质上就是：

  1. 学会银行的"语言"（API文档）
     → 它要什么格式、什么字段、什么签名算法

  2. 证明你是谁（认证）
     → merchantId + 签名/证书，证明这个请求来自你，不是黑客

  3. 安全通信（HTTPS + 签名双向验证）
     → 你发出去的请求，银行能验证是你的
     → 银行回调你，你能验证是银行的（不是黑客伪造的）

  4. 处理异步结果（回调 + 轮询）
     → 支付结果不是同步的，要处理回调、超时重试、状态查询

  5. 幂等处理（防止重复扣款）
     → 网络超时可能导致重复请求，要用订单号去重

记住这5点，对接任何银行的逻辑都是一样的，
区别只是"语言"（格式、签名算法）不同而已。
```

---

*文档版本：2026-04-17*
*覆盖渠道：渣打SCB / 汇丰HSBC / 中国建设银行CCB / 支付宝Alipay*

