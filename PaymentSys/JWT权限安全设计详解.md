# JWT + GroupId 权限安全设计详解

> 解答疑问："根据 groupId 判断是否财务，会不会被前端篡改导致越权？"

---

## 一、直接回答：安全，但前提是登录做对了

**短回答：**
- 前端**无法篡改** JWT 里的 groupId（签名保护）
- 但有一个关键前提：**登录时 groupId 必须从 DB 查，不能接受客户端传入**
- 本系统已正确实现这个前提

---

## 二、JWT 的防篡改原理

```
JWT = Base64(Header) + "." + Base64(Payload) + "." + Signature

Signature = HMAC-SHA256(Header + "." + Payload, 服务端 secret)
```

```
正常 Token：
  Header:  eyJhbGciOiJIUzI1NiJ9
  Payload: eyJ1c2VySWQiOiJVMDAxIiwicGFyZW50R3JvdXBJZCI6MjAwfQ==
                                                         ↑
                                                     parentGroupId=200（普通用户）
  Signature: xK3f9mP2... ← 用服务端 secret 计算出来的

攻击者尝试篡改：
  把 Payload 里的 parentGroupId 改成 345（财务）
  → Payload 变了，原 Signature 不再匹配新 Payload
  → 服务端验签失败 → 直接 401 拒绝

攻击者没有 secret，无法重新生成有效的 Signature
```

**结论：** 只要 secret 不被泄露，JWT payload 就无法被篡改。

---

## 三、真正的危险点——登录接口写错了

### ❌ 错误写法（危险！）

```java
// 客户端传来的请求：
// { "username": "hacker", "password": "xxx", "groupId": 345 }
//                                            ↑ 攻击者自己填 345

@PostMapping("/login")
public LoginResponse login(@RequestBody LoginRequest request) {
    // 直接用客户端传来的 groupId 生成 token ← 完全不查 DB！
    String token = jwtUtil.generateToken(
            userId,
            username,
            request.getGroupId(),         // ← 危险！信任了客户端
            request.getParentGroupId()    // ← 危险！
    );
}
```

这种写法哪怕 JWT 签名再强，攻击者只需在登录时传 `"parentGroupId": 345`，就能拿到一个财务权限的 Token。**整个权限体系形同虚设。**

### ✅ 正确写法（本系统的实现）

```java
// 客户端只能传：
// { "username": "hacker", "password": "xxx" }
// ← 根本没有 groupId 字段！LoginRequest 里没定义这个字段

@PostMapping("/login")
public LoginResponse login(@RequestBody LoginRequest request) {  // LoginRequest 只有 username+password

    // Step 1: 从 DB 查用户（得到用户的真实 group_id）
    SysUser user = sysUserMapper.selectByUsername(request.getUsername());
    // user.groupId = 200（这是 DB 里的，攻击者改不了）

    // Step 2: BCrypt 验密码

    // Step 3: 用 user.groupId 再查 SYS_GROUP 得到真实 parentGroupId
    Integer parentGroupId = sysGroupMapper.selectParentGroupId(user.getGroupId());
    // parentGroupId = 200（普通用户），绝对不是 345

    // Step 4: 用 DB 查来的值生成 JWT
    String token = jwtUtil.generateToken(userId, username,
            user.getGroupId(),   // ← DB 来的，不是客户端传的
            parentGroupId        // ← DB 来的，不是客户端传的
    );
}
```

---

## 四、完整安全链条

```
攻击者想获取财务权限的过程：

第一关：登录接口（AuthController）
  攻击者传 {"username":"hacker","password":"xxx"}
  → LoginRequest 没有 groupId 字段，传了也被忽略
  → 服务端从 DB 查真实 groupId（200=普通用户）
  → JWT 里写入 parentGroupId=200
  ✅ 无法在登录阶段注入虚假角色

第二关：JWT 签名验证（AuthFilter）
  攻击者修改 Token payload，把 parentGroupId 改成 345
  → 签名验证失败 → 401
  ✅ 无法篡改 Token

第三关：拦截器（FinanceAuthInterceptor）
  访问 /api/finance/**
  → 检查 parentGroupId == 345 or 59
  → 攻击者的 Token 里是 200 → 403
  ✅ 无法访问财务接口

第四关：AOP（FinanceAuthAspect + @RequireFinance）
  即使绕过 HTTP 层直接调 Service
  → 方法执行前检查 UserContext.isFinance()
  → 仍然 403
  ✅ Service 层也有保护

第五关：DB 二次验权（RefundApprovalService）
  即使财务 Token 被盗用（如 XSS 攻击）
  → 退款审批时调 authService.verifyRoleFromDB()
  → 实时查 DB 确认该 userId 当前是否还是财务
  → 若角色已被撤销 → 拒绝
  ✅ Token 盗用也难以得逞
```

---

## 五、剩余的真实风险和应对方案

### 风险1：Secret 泄露

| 场景 | 攻击者能做什么 | 应对 |
|------|-------------|------|
| 代码仓库里写死 secret | 任意生成有效 Token | **生产必须用环境变量** `JWT_SECRET=xxx` |
| 服务器被入侵 | 同上 | 定期轮换 secret，轮换后所有 Token 失效 |

```yaml
# ✅ 正确：生产用环境变量
jwt:
  secret: ${JWT_SECRET}   # Kubernetes Secret / 配置中心注入

# ❌ 错误：写死在代码里
jwt:
  secret: "my-secret-123"  # ← 千万不能这样！
```

### 风险2：Token 被盗（XSS、日志泄漏等）

| 措施 | 效果 |
|------|------|
| 短有效期（8~24小时）| 盗走的 Token 很快失效 |
| DB 二次验权（已实现）| 高风险操作实时确认角色 |
| HTTPS 传输 | 防中间人窃取 Token |
| 不在 URL 里放 Token | 防日志泄漏 |

### 风险3：员工角色变更，旧 Token 仍有效

```
场景：财务员工被调岗，不再是财务
     → DB 里 group_id 已更新
     → 但旧 JWT 有效期还剩 20 小时
     → 用旧 Token 还能通过 JWT 签名检查

应对方案（已实现）：
  退款审批 = 高风险操作 → authService.verifyRoleFromDB(userId, "FINANCE")
  → 实时查 DB，发现不再是财务 → 拒绝
  
  一般查询操作 = 低风险 → JWT 验证够了，不重复查 DB（性能考虑）
```

---

## 六、密码安全：BCrypt 而不是 MD5

```java
// ❌ 错误：MD5（已被破解）
String hash = DigestUtils.md5Hex(password);  // 完全不安全

// ❌ 错误：SHA256（无盐，彩虹表攻击）
String hash = DigestUtils.sha256Hex(password);  // 还是不够安全

// ✅ 正确：BCrypt（本系统使用）
String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
// gensalt(10) = cost factor 10，约100ms计算时间，暴力破解成本极高
// 每次生成不同的 salt，同样密码哈希值不同，无法彩虹表攻击

// 验证
boolean match = BCrypt.checkpw(inputPassword, storedHash);
```

BCrypt 的 cost=10 意味着破解一个密码需要约 100ms，破解 100 万个密码需要约 28 小时，实际上不可行。

---

## 七、账号锁定：防止暴力破解

```java
// 连续失败 5 次 → 自动锁定
// 实现在 SysUserMapper.incrementFailedAttempts()：
UPDATE SYS_USER
SET FAILED_ATTEMPTS = FAILED_ATTEMPTS + 1,
    LOCKED = CASE WHEN FAILED_ATTEMPTS + 1 >= 5 THEN 1 ELSE 0 END
WHERE USER_ID = ?

// 锁定后不论密码是否正确都拒绝登录
```

---

## 八、总结

| 安全点 | 实现 | 代码位置 |
|--------|------|---------|
| groupId 来自 DB，不信任客户端 | 登录只接受 username+password | `LoginRequest.java`（无 groupId 字段）|
| JWT payload 防篡改 | HS256 签名 | `JwtUtil.java` |
| JWT secret 配置安全 | 环境变量注入 | `application.yml` |
| 高风险操作二次验 DB | `verifyRoleFromDB()` | `AuthService.java` |
| 密码 BCrypt 哈希 | Hutool BCrypt | `AuthService.hashPassword()` |
| 账号暴力破解防护 | 失败5次锁定 | `SysUserMapper.incrementFailedAttempts()` |
| 登录审计 | `SYS_LOGIN_LOG` 表 | `auth_schema.sql` |
| 财务路径拦截 | `FinanceAuthInterceptor` | `WebMvcConfig.java` |
| Service 方法级防护 | `@RequireFinance` AOP | `FinanceAuthAspect.java` |
