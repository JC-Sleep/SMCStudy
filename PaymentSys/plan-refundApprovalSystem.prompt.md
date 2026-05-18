# Plan: 退款审批系统 + 支付路由修复

两件事并行完成：①搭建完整的退款申请→财务审批→执行退款的安全流程（groupId父节点=345验证财务身份）；②修复 `PaymentGatewayRouter` 中的初始化Bug。

---

## Steps

### Step 1 — 新建两张 DB 表
在 `src/main/resources/db/refund_schema.sql` 里添加：

- `REFUND_APPLICATION`（申请单表）
  - 主键 ID（Snowflake）
  - `TRANSACTION_ID` FK → PAYMENT_TRANSACTION
  - `ORDER_REFERENCE`
  - `APPLICANT_USER_ID`（申请人）
  - `REFUND_AMOUNT`（申请退款金额）
  - `REFUND_REASON`
  - `STATUS` CHECK IN (`PENDING_REVIEW`, `APPROVED`, `REJECTED`, `EXECUTING`, `COMPLETED`, `FAILED`)
  - `REVIEWED_BY`（财务审批人 userId）
  - `REVIEWED_AT`（审批时间）
  - `REVIEW_REMARK`（审批备注）
  - `REFUND_NO`（实际退款单号，执行后填入）
  - `VERSION`（乐观锁）
  - `CREATE_TIME`, `UPDATE_TIME`, `CREATE_USER`, `UPDATE_USER`
  - UNIQUE 约束：同一笔交易只能有一条 PENDING_REVIEW 或 APPROVED（防重复申请）

- `REFUND_AUDIT_LOG`（不可变操作流水，只能 INSERT，不允许 UPDATE/DELETE）
  - 主键 ID
  - `APPLICATION_ID` FK → REFUND_APPLICATION
  - `TRANSACTION_ID`
  - `ACTION`（`APPLY` / `APPROVE` / `REJECT` / `EXECUTE_SUCCESS` / `EXECUTE_FAILED`）
  - `OPERATOR_USER_ID`
  - `OPERATOR_GROUP_ID`
  - `OPERATOR_IP`（客户端IP，防抵赖）
  - `REMARK`
  - `CREATE_TIME`（只有创建时间，无更新时间）

新增 `RefundApplicationStatus` 枚举（`PENDING_REVIEW / APPROVED / REJECTED / EXECUTING / COMPLETED / FAILED`）。

---

### Step 2 — 实现身份上下文与财务权限拦截
新建 `security/` 包：

- **`UserContext.java`**（POJO）
  ```
  userId, groupId, parentGroupId, userIp
  ```

- **`UserContextHolder.java`**（ThreadLocal 工具类）
  ```java
  private static final ThreadLocal<UserContext> holder = new ThreadLocal<>();
  static void set(UserContext ctx) / static UserContext get() / static void clear()
  ```

- **`FinanceAuthInterceptor.java`**（`HandlerInterceptor`）
  - `preHandle()`：读 Header `X-User-Id` / `X-Group-Id` / `X-Parent-Group-Id`
  - 任何一个 Header 缺失 → 401
  - `parentGroupId != 345` → 403，日志记录尝试访问者信息
  - 通过则写入 `UserContextHolder`
  - `afterCompletion()`：`UserContextHolder.clear()`（防 ThreadLocal 泄漏）

- **`@RequireFinance`**（自定义注解，用于 Controller/Service 方法）

- **`FinanceAuthAspect.java`**（AOP 双保险）
  - 拦截 `@RequireFinance` 方法，调用 `UserContextHolder.get()` 校验 parentGroupId = 345

- **`WebMvcConfig.java`**
  - 注册 `FinanceAuthInterceptor`，路径 `/api/finance/**`

---

### Step 3 — 新建 Entity / Mapper / XML

**Entities：**

- `entity/RefundApplication.java`（继承 `BaseEntity`，含 `@Version` 乐观锁）
- `entity/RefundAuditLog.java`（不继承 BaseEntity，只有 `CREATE_TIME`，无 UPDATE）

**Mappers：**

- `mapper/RefundApplicationMapper.java`（extends `BaseMapper<RefundApplication>`）
  - 自定义方法：`selectPendingByTransactionId(String transactionId)`
  - 自定义方法：`selectPageByStatus(Page page, String status)`（分页查列表）

- `mapper/RefundAuditLogMapper.java`（extends `BaseMapper<RefundAuditLog>`）

**Mapper XML：**

- `resources/mapper/RefundApplicationMapper.xml`
- `resources/mapper/RefundAuditLogMapper.xml`

---

### Step 4 — 新建 Service 层

**`RefundApplicationService.java`**（用户端）

- `applyRefund(RefundApplyRequest request, UserContext applicant)`
  1. 查 `PAYMENT_TRANSACTION`，验证状态 = SUCCESS
  2. 验证 `refundAmount <= transaction.amount`
  3. 检查是否存在同一笔交易的 PENDING_REVIEW / APPROVED 申请（防重复提交）
  4. INSERT `REFUND_APPLICATION`（状态 = PENDING_REVIEW）
  5. INSERT `REFUND_AUDIT_LOG`（action = APPLY）
  6. 返回 applicationId 给用户

- `getApplicationStatus(String applicationId)` — 用户查询自己申请的进度

**`RefundApprovalService.java`**（财务端）

- `listPendingApplications(Page page)` — 分页查 PENDING_REVIEW 列表

- `approveApplication(String applicationId, RefundApproveRequest req, UserContext finance)`
  1. 乐观锁 UPDATE：PENDING_REVIEW → APPROVED（version 检查，并发审批保护）
  2. 记录 `reviewedBy`, `reviewedAt`, `reviewRemark`
  3. INSERT 审计日志（action = APPROVE，记录 IP）
  4. 触发异步执行 `executeRefundAsync(applicationId)`

- `rejectApplication(String applicationId, RefundApproveRequest req, UserContext finance)`
  1. 乐观锁 UPDATE：PENDING_REVIEW → REJECTED
  2. INSERT 审计日志（action = REJECT）

- `executeRefundAsync(String applicationId)` — `@Async`
  1. UPDATE 状态：APPROVED → EXECUTING（乐观锁，防并发重复执行）
  2. 调用 `PaymentServiceEnhanced.refund()`（已有乐观锁防重复）
  3. 成功 → COMPLETED；失败 → FAILED（均写审计日志）
  4. 失败时告警（log.error，可接 DingTalk/邮件告警）

---

### Step 5 — 新建两个 Controller

**`RefundApplicationController.java`**（`/api/refund/`，用户端，无需财务权限）

```
POST /api/refund/apply
  Body: { transactionId, refundAmount, refundReason }
  → 返回 applicationId

GET /api/refund/status/{applicationId}
  → 返回当前状态（只返回自己提交的申请）
```

**`RefundApprovalController.java`**（`/api/finance/refund/`，全部加 `@RequireFinance`）

```
GET /api/finance/refund/list?status=PENDING_REVIEW&page=1&size=20
  → 分页返回申请列表（含申请人、金额、原因、申请时间）

POST /api/finance/refund/approve
  Body: { applicationId, reviewRemark }
  → 审批通过，触发异步退款

POST /api/finance/refund/reject
  Body: { applicationId, reviewRemark }
  → 拒绝申请，通知用户

GET /api/finance/refund/audit-log/{applicationId}
  → 查看该申请的完整审计日志（不可篡改）
```

---

### Step 6 — 修复 `PaymentGatewayRouter` Bug

**问题：**
- `init()` 跳过了 `isAvailable()=false` 的网关（如配置缺失），导致补全配置后须重启才能路由到该渠道
- `paymentMethodGatewayMap` 声明了但从不填充、从不使用 → 死代码
- `selectGateway()` 未在路由失败时提供足够的诊断信息

**修复：**

```java
@PostConstruct
public void init() {
    // 修复：始终注册所有网关（无论是否 available）
    for (PaymentGateway gateway : gateways) {
        gatewayMap.put(gateway.getChannel(), gateway);
        log.info("注册支付网关: {} - {} (available={})",
            gateway.getChannel().getCode(), gateway.getChannelName(), gateway.isAvailable());
    }
    // 删除 paymentMethodGatewayMap 相关死代码
}
```

- `getGateway(PaymentChannel)` 保留 `isAvailable()` 检查（不变）
- `selectGateway()` 路由失败时日志输出所有已注册渠道及其 available 状态，方便排查
- 删除 `paymentMethodGatewayMap` 字段声明

---

### Step 7 — 补充异常处理 + 修 Typo

- `GlobalExceptionHandler.java` 第 1 行：`iupackage` → `package`
- 新建 `exception/UnauthorizedException.java`（403 场景）
- `GlobalExceptionHandler` 新增：
  ```java
  @ExceptionHandler(UnauthorizedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ApiResponse<?> handleUnauthorized(UnauthorizedException e)
  ```

---

### Step 8 — 写详细 MD 文档

新建 `PaymentSys/退款审批系统设计文档.md`，涵盖：

1. **背景与设计目标** — 为什么需要审批流，防止哪些风险
2. **DB 表设计** — 字段说明、约束说明、索引说明
3. **权限验证原理** — groupId 树结构图，parentGroupId=345 代表财务部，请求Header说明
4. **完整时序图** — 用户申请 → 财务审批 → 异步执行 → 结果通知
5. **各接口说明** — 请求/响应示例
6. **安全设计清单**
   - 防重复申请（DB 唯一约束）
   - 防并发审批（乐观锁 version）
   - 防伪造身份（Header 必填，上游 API Gateway 负责签名验证）
   - 审计不可篡改（REFUND_AUDIT_LOG 只 INSERT）
   - 操作人 IP 记录（防抵赖）
   - 财务接口双重保护（Interceptor + AOP）
7. **财务操作指南** — 如何登录、如何审批、如何处理 FAILED 状态

---

## Further Considerations（待确认后完善）

1. **财务身份验证 Header 来源** — 目前方案读 HTTP Header（`X-User-Id / X-Group-Id / X-Parent-Group-Id`），适合 API Gateway 转发已验证 JWT 的场景。如果系统有自己的 JWT 登录，需要改为在 Filter 里解析 JWT Token 提取 claims。**请确认：你们现有登录系统是哪种方式？Header 注入 / JWT / Session Cookie？**

2. **退款执行同步 vs 异步** — 银行退款 API 可能超时（渣打最多 10s），建议审批通过后**异步执行**（状态先变 EXECUTING，结果回填），财务页面轮询或推送结果。**你们是否接受异步方式，还是必须同步返回结果？**

3. **多次部分退款** — 同一笔交易是否支持多次提交部分退款申请（总额 ≤ 原金额）？如需支持，`RefundApplicationService.applyRefund()` 需加 `SUM(refund_amount) WHERE transaction_id=? AND status NOT IN ('REJECTED','FAILED')` 校验。**请确认是否需要。**
