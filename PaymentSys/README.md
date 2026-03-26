# 支付系统 - 渣打银行对接

## 项目简介

基于 SpringBoot 的支付网关系统，对接渣打银行（Standard Chartered Bank），重点解决支付回调处理、交易对账以及防止"客户已付款但订单失败"的场景。

## 核心特性

### ✅ 已实现功能

1. **支付发起** - 完整的支付流程，包含幂等性校验
2. **回调处理** - 异步处理银行回调，带签名验证
3. **自动对账** - 每30分钟自动对账TIMEOUT交易
4. **乐观锁** - 防止并发场景下的状态覆盖
5. **完整审计** - 所有回调和状态变更都有日志

### 🔑 核心设计理念

**银行是真实来源** - 当有疑问时，查询银行并更新本地状态

## 技术栈

- **框架**: Spring Boot 2.6.13
- **ORM**: MyBatis-Plus 3.5.3.1
- **数据库**: Oracle 19c
- **连接池**: HikariCP
- **工具类**: Hutool 5.8.11
- **JSON**: Fastjson 1.2.83

## 项目结构

```
PaymentSys/
├── src/main/
│   ├── java/sys/smc/payment/
│   │   ├── config/              # 配置类
│   │   │   ├── AsyncConfig.java          # 异步任务配置
│   │   │   ├── MyBatisPlusConfig.java    # MyBatis-Plus配置（乐观锁）
│   │   │   └── GlobalExceptionHandler.java # 全局异常处理
│   │   ├── controller/          # 控制器
│   │   │   ├── PaymentController.java           # 支付接口
│   │   │   └── PaymentCallbackController.java   # 回调接口
│   │   ├── dto/                 # 数据传输对象
│   │   │   ├── PaymentInitRequest.java
│   │   │   ├── PaymentInitResponse.java
│   │   │   ├── PaymentCallbackData.java
│   │   │   └── ApiResponse.java
│   │   ├── entity/              # 实体类
│   │   │   ├── BaseEntity.java
│   │   │   ├── PaymentTransaction.java
│   │   │   ├── PaymentCallbackLog.java
│   │   │   └── PaymentReconciliation.java
│   │   ├── enums/               # 枚举
│   │   │   └── PaymentStatus.java
│   │   ├── exception/           # 异常
│   │   │   ├── PaymentException.java
│   │   │   ├── GatewayException.java
│   │   │   └── OptimisticLockException.java
│   │   ├── gateway/             # 网关客户端
│   │   │   ├── StandardCharteredGatewayClient.java
│   │   │   └── dto/
│   │   │       ├── GatewayPaymentResponse.java
│   │   │       └── GatewayTransactionStatus.java
│   │   ├── job/                 # 定时任务
│   │   │   └── PaymentReconciliationJob.java  # 对账任务（核心）
│   │   ├── mapper/              # Mapper接口
│   │   │   ├── PaymentTransactionMapper.java
│   │   │   ├── PaymentCallbackLogMapper.java
│   │   │   └── PaymentReconciliationMapper.java
│   │   ├── service/             # 服务层
│   │   │   ├── PaymentService.java           # 支付服务
│   │   │   └── PaymentCallbackService.java   # 回调服务
│   │   ├── util/                # 工具类
│   │   │   └── SignatureVerifier.java
│   │   └── PaymentSystemApplication.java  # 启动类
│   └── resources/
│       ├── application.yml      # 配置文件
│       ├── db/
│       │   └── schema.sql       # 数据库初始化脚本
│       └── mapper/
│           └── PaymentTransactionMapper.xml
└── pom.xml
```

## 数据库设计

### 核心表

1. **PAYMENT_TRANSACTION** - 支付交易主表（含乐观锁版本号）
2. **PAYMENT_CALLBACK_LOG** - 回调日志表（记录所有回调）
3. **PAYMENT_RECONCILIATION** - 对账记录表
4. **PAYMENT_ORDER_MAPPING** - 支付订单映射表
5. **PAYMENT_CONFIG** - 支付配置表

详细表结构见：`src/main/resources/db/schema.sql`

## 快速开始

### 1. 环境要求

- JDK 8+
- Oracle 19c+
- Maven 3.6+

### 2. 配置数据库

```bash
# 执行数据库初始化脚本
sqlplus username/password@tnsname @src/main/resources/db/schema.sql
```

### 3. 配置应用

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521:ORCL
    username: your_username
    password: your_password

scb:
  api:
    endpoint: https://api.standardchartered.com/payment
    key: your_api_key
    secret: your_api_secret
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

或者打包后运行：

```bash
mvn clean package
java -jar target/PaymentSys-1.0.0.jar
```

## API 接口

### 1. 发起支付

**请求**:
```http
POST /api/payment/initiate
Content-Type: application/json

{
  "orderReference": "CSP001234567",
  "userId": "33xxxx",
  "amount": 176.00,
  "returnUrl": "https://shop.example.com/payment/return"
}
```

**响应**:
```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "transactionId": "TXN123456",
    "paymentUrl": "https://payment.standardchartered.com/pay?token=xxx",
    "status": "PENDING"
  }
}
```

### 2. 查询支付状态

**请求**:
```http
GET /api/payment/status/{transactionId}
```

### 3. 回调接口（银行调用）

**请求**:
```http
POST /api/payment/callback/standard-chartered
X-Signature: {HMAC-SHA256签名}
Content-Type: application/json

{银行回调数据}
```

详细API文档见：[支付系统设计方案-渣打银行对接.md](支付系统设计方案-渣打银行对接.md)

## 核心功能说明

### 🔐 幂等性保证

使用 `idempotencyKey`（订单号+金额+时间戳）防止重复支付：

```java
String idempotencyKey = orderReference + "_" + amount + "_" + timestamp;
```

数据库唯一约束确保同一个请求不会创建多笔交易。

### 🔄 异步回调处理

使用 `@Async` 异步处理回调，立即返回200 OK：

```java
@Async("callbackExecutor")
public void processCallback(String rawBody, ...) {
    // 异步处理
}
```

防止银行重试风暴，提高吞吐量。

### 🔒 乐观锁

使用 MyBatis-Plus 的 `@Version` 注解：

```java
@Version
private Integer version;
```

防止并发场景下的状态覆盖。

### ⏰ 自动对账

每30分钟自动对账 TIMEOUT/PENDING 交易：

```java
@Scheduled(cron = "0 */30 * * * ?")
public void reconcileTimeoutTransactions() {
    // 查询银行状态
    // 比对本地状态
    // 修正不匹配
}
```

这是防止"客户已付款但系统显示超时"的**关键机制**。

## 故障排查

### 场景：客户声称支付成功但系统显示TIMEOUT

#### 1. 检查交易记录

```sql
SELECT * FROM PAYMENT_TRANSACTION 
WHERE ORDER_REFERENCE = 'CSP001xxxxx'
ORDER BY CREATE_TIME DESC;
```

#### 2. 检查回调日志

```sql
SELECT * FROM PAYMENT_CALLBACK_LOG 
WHERE TRANSACTION_ID = 'TXN...'
ORDER BY CALLBACK_TIME DESC;
```

#### 3. 检查对账记录

```sql
SELECT * FROM PAYMENT_RECONCILIATION 
WHERE MISMATCH_TRANSACTION_IDS LIKE '%TXN...%'
ORDER BY RECONCILIATION_DATE DESC;
```

#### 4. 手动修正（如银行确认支付成功）

```sql
UPDATE PAYMENT_TRANSACTION 
SET PAYMENT_STATUS = 'SUCCESS',
    PREVIOUS_STATUS = 'TIMEOUT',
    RECONCILIATION_STATUS = 'MANUAL_FIX',
    REMARKS = '手动对账 - 银行确认支付成功'
WHERE TRANSACTION_ID = 'TXN...';
COMMIT;
```

详细故障处理手册见：[支付系统设计方案-渣打银行对接.md](支付系统设计方案-渣打银行对接.md) 第9.4节

## 监控指标

建议监控以下指标：

- ✅ 支付成功率
- ⏱️ 平均支付处理时间
- 📞 回调处理成功率
- ⏰ TIMEOUT交易数量
- 🔍 对账不匹配数量

## 日志说明

### 日志级别

- `DEBUG` - 详细的调试信息（包括签名、请求体等）
- `INFO` - 关键业务流程（支付发起、回调接收、对账完成等）
- `WARN` - 需要注意的情况（重复请求、状态不匹配等）
- `ERROR` - 错误情况（签名验证失败、网关调用失败等）

### 日志位置

- 控制台：实时查看
- 文件：`logs/payment-system.log`（按日滚动，保留30天）

## 安全说明

### 签名验证

所有回调都会验证 HMAC-SHA256 签名：

```java
String signature = SecureUtil.hmacSha256(apiSecret).digestHex(data);
```

### 敏感信息

- API密钥应使用环境变量配置
- 永不存储完整卡号
- 日志中脱敏处理

## 待完善功能

- [ ] IP白名单校验
- [ ] 邮件告警通知
- [ ] 对账报告生成
- [ ] 订单系统通知
- [ ] 监控指标统计
- [ ] 单元测试
- [ ] 性能压测

## 许可证

本项目仅用于学习目的。

## 联系方式

如有问题，请查看：[支付系统设计方案-渣打银行对接.md](支付系统设计方案-渣打银行对接.md)

