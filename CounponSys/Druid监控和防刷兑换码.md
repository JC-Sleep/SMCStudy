````markdown
# CouponSys — Druid监控 + 分布式兑换码防刷 完整方案

> 更新于 2026-04-20 v2 | 新增：签名算法对比 / CAS原理详解 / 合法码兑换失败处理 / 6层防刷详解

---

## 一、数据库连接监控：为什么选 Druid，不自己写？

### 1.1 三种方案对比

| 方案 | 能力 | 长连接排查 | 成本 |
|------|------|-----------|------|
| **HikariCP（原来）** | 仅连接池 | ❌ 无调用栈，看不出谁卡住 | 零 |
| **自己写监控代码** | 只能看数字（activeCount等） | ❌ 依然不知道是哪行SQL | 高（维护成本大） |
| **Druid（推荐）** ✅ | SQL统计+慢查询+Web控制台+泄漏调用栈 | ✅ **直接打印是哪个类/哪行代码拿了连接没还** | 低（加一个依赖） |

**结论：不要自己写。** Druid 的 `log-abandoned=true` 会直接在日志里打印：

```
[Druid-ConnectionPool-Destroy] abandon connection, owner thread: http-nio-8090-exec-3
  at sys.smc.coupon.service.impl.SeckillServiceImpl.grabCoupon(SeckillServiceImpl.java:87)
  at sys.smc.coupon.controller.SeckillController.grabCoupon(SeckillController.java:61)
```

一眼定位：`SeckillServiceImpl.java:87` 这行拿了连接，3分钟没还。自己写只能看到 `activeCount=18/20`，不知道是谁占用。

### 1.2 最怕的场景：长连接卡死

| 原因 | 现象 | Druid如何发现 |
|------|------|--------------|
| 大报表SQL（全表扫描） | 一条SQL跑了30分钟 | 慢SQL页面 `/druid/sql.html` 立即显示 |
| 秒杀期间行锁等待 | Oracle `SELECT FOR UPDATE` 等不到锁 | `waitThreadCount` 急剧增加，告警触发 |
| 服务端忘记关连接 | 连接池慢慢耗尽，新请求全部超时 | `log-abandoned` 打印泄漏调用栈 |
| 网络闪断后僵尸连接 | 连接对象存在但TCP已断 | `validation-query: SELECT 1 FROM DUAL` 心跳检测 |

### 1.3 关键配置

```yaml
druid:
  remove-abandoned: true
  remove-abandoned-timeout: 180      # 3分钟未归还 = 判定泄漏
  log-abandoned: true                # ★ 打印泄漏连接完整调用栈
  connection-properties: druid.stat.mergeSql=true;druid.stat.slowSqlMillis=2000
  validation-query: SELECT 1 FROM DUAL
  test-while-idle: true
  filters: stat,wall,slf4j
  stat-view-servlet:
    enabled: true
    url-pattern: /druid/*
    login-username: admin
    login-password: admin123
    allow: 127.0.0.1                 # 生产只允许内网！
```

### 1.4 排查3步

```
1. 连接池告警（activeCount>80% 或 waitThreadCount>5）
2. 打开 /druid/sql.html → 按"执行中"排序 → 找执行时间>30秒的SQL
3. 查日志 [Druid-ConnectionPool-Destroy] → 看调用栈 → 定位类+行号
```

### 1.5 访问方式

| 入口 | URL |
|------|-----|
| Web控制台 | http://host:8090/druid |
| SQL监控 | http://host:8090/druid/sql.html |
| 监控API | GET /api/monitor/db |

---

## 二、3实例分布式 + Nginx 对兑换码的影响

### 2.1 架构

```
用户 → Nginx → 实例A/B/C → 共享 Oracle + Redis Cluster + Kafka
```

### 2.2 四个风险点分析

| 风险 | 是否已解决 | 解决方式 |
|------|-----------|---------|
| 同码并发打到两个实例→重复兑换 | ✅ | Redis分布式锁 + DB CAS双重保险 |
| IP限速在多实例下不准 | ✅ | Redis共享计数器，三实例共用同一Key |
| HMAC密钥各实例不同→验签失败 | ⚠️ 需配置 | 必须三实例使用相同密钥，用K8s Secret注入 |
| Nginx ip_hash导致流量不均 | ✅ 不需要 | 系统无状态，用默认轮询即可 |

### 2.3 HMAC密钥多实例统一方案

```yaml
# 所有实例必须相同，生产用环境变量注入
coupon.code.redeem.hmac-secret: ${COUPON_HMAC_SECRET}
```

```yaml
# k8s deployment.yaml
env:
  - name: COUPON_HMAC_SECRET
    valueFrom:
      secretKeyRef:
        name: coupon-secrets
        key: hmac-secret
```

### 2.4 各防刷层分布式有效性

| 防刷层 | 分布式安全？ | 原因 |
|--------|------------|------|
| HMAC签名 | ✅（需同密钥） | 无状态纯计算 |
| IP/用户限速 | ✅ | Redis共享，跨实例原子计数 |
| 分布式锁 | ✅ | 锁在Redis，跨实例互斥 |
| DB CAS | ✅ | Oracle行锁，天然并发安全 |
| 失败次数锁定 | ✅ | DB字段，不依赖实例内存 |

**结论：3实例部署对兑换码功能无任何影响，只需保证HMAC密钥一致。**

---

## 三、HMAC-SHA256能完全防造码吗？与其他算法对比

### 3.1 能防哪些攻击？不能防哪些？

| 攻击类型 | HMAC-SHA256能防吗？ | 原因 |
|---------|-------------------|------|
| 随机枚举猜码 | ✅ **完全能防** | 签名部分有36⁴=167万种可能，配合限速≈破解需2799小时 |
| 伪造新码 | ✅ **完全能防** | 没有私钥无法生成合法签名 |
| 重放已用的真实码 | ❌ **不能防** | 这是DB层STATUS=0的职责，HMAC只管格式合法 |
| 内部人员泄露码 | ❌ **不能防** | HMAC不是加密，不保护码的传输安全 |
| 社工骗取别人的码 | ❌ **不能防** | 属于社会工程学攻击，技术层无法解决 |

**结论**：HMAC只负责"这个码的格式是本系统颁发的"，不负责"这个码没有被用过"。两件事由不同层保证：
- 格式合法 → HMAC（第1层）
- 只能用一次 → DB CAS（第5层）

---

### 3.2 兑换码生成算法全对比

| 算法 | 示例码 | 优点 | 缺点 | 适用场景 |
|------|--------|------|------|---------|
| **纯随机UUID** | `A3F7-2K5X-B8M1-ZQ3N` | 极简单，碰撞概率极低 | ① 无法本地验证，每次必须查DB<br>② 攻击者暴力枚举时DB压力大 | 内部系统，低并发 |
| **纯数字流水** | `20260417-001` | 极简单 | ① 极好猜<br>② 无任何防伪手段 | 不推荐任何场景 |
| **Base62随机** | `aB3xK9mZ` | 码短，用户友好 | ① 无校验位，合法性只能查DB<br>② 被暴力枚举时无法在DB查前拦截 | 低价值码（如体验券） |
| **Luhn校验码** | `4532015112830366` | 有1位校验，可本地验证输入错误 | ① 校验极弱，只能发现随机输错<br>② 攻击者轻松计算出合法格式的假码 | 信用卡号格式验证（非安全场景） |
| **序列号+CRC32** | `SMC-2026-A3F7C2` | 有4位CRC校验，轻量 | ① CRC32算法公开，攻击者可计算合法码<br>② 不涉及私钥，无法防伪造 | 软件序列号（非金融场景） |
| **HMAC-SHA256（采用）** ✅ | `CP-2K5XM1-A3F7` | ① 私钥签名，无私钥无法伪造<br>② 本地验证，无效码不查DB<br>③ 算法成熟，无已知破解 | ① 码略长（约14位）<br>② 需保护HMAC私钥 | **推荐：金融/营销高价值券** |
| **JWT风格** | `eyJh...很长` | 可携带任意载荷（过期时间等） | ① 码太长，用户体验差<br>② 对于简单兑换场景过于复杂 | Token认证，不适合用户输入 |
| **AES加密码** | `X7K2-M9QP-...` | 内容加密，可藏信息 | ① 解密需要服务端，与HMAC优势相同<br>② 码长度不可控<br>③ 过度设计 | 特殊隐私场景 |

### 3.3 为什么最终选 HMAC-SHA256

```
需求：用户输入短码 → 系统快速判断合法性 → 防暴力枚举

HMAC选择理由：
  ✅ 签名基于私钥，攻击者无法反向推算
  ✅ 验签是本地计算（纳秒级），不用查Redis/DB → 无效码零DB压力
  ✅ 码长合理（约14字符），用户可手工输入
  ✅ SHA256目前无已知碰撞，工业级安全标准
  ✅ Java内置支持（Hutool封装），代码5行实现

UUID缺点（为什么不选）：
  ❌ 每次都要查DB才知道合不合法
  ❌ 攻击者每秒发10万个请求枚举，DB必死

Base62缺点（为什么不选）：
  ❌ 与UUID同理，无本地验证能力
```

---

## 四、CAS是什么？DB层如何防重复兑换？

### 4.1 CAS通俗解释

**CAS = Compare And Swap（比较并交换）**

不是加锁，而是一种**乐观的原子更新**方式，核心思想是：

> "我只在数据**还是我期望的那个值时**才去改它，如果已经被别人改了，我就放弃。"

### 4.2 生活类比

```
场景：超市最后一瓶矿泉水

传统加锁（悲观锁）：
  甲：我要拿，先把货架锁起来，别人等着
  甲拿完 → 解锁 → 乙才能看

CAS（乐观锁）：
  甲：我看到货架上有1瓶（期望值=有货）
  乙：我也看到有1瓶
  甲先伸手：货架变成0瓶 ✅ 成功
  乙伸手：货架已经是0瓶了，不等于我期望的"有货" ❌ 放弃
```

### 4.3 兑换码中的CAS：WHERE STATUS=0

```sql
-- 这一行SQL就是CAS的完整实现
UPDATE T_COUPON_REDEEM_CODE
SET STATUS = 1,
    USER_ID = 'user123',
    REDEEM_TIME = SYSTIMESTAMP
WHERE CODE = 'CP-2K5XM1-A3F7'
  AND STATUS = 0              -- ← 这就是"比较"：只在未使用时才更新
```

```
执行结果：
  返回 affected=1 → 说明更新成功，这个请求赢了，可以发券
  返回 affected=0 → 说明STATUS已经不是0了（被别人改了），当前请求失败

无论多少个并发请求同时执行这条SQL：
  Oracle数据库内部用行锁保证同一时刻只有一个请求能把STATUS从0改成1
  其他所有请求拿到 affected=0，全部失败
  → 绝对不会出现两个用户都兑换成功同一张码
```

### 4.4 CAS vs 传统加锁对比

| 对比项 | 传统加锁（SELECT FOR UPDATE） | CAS（WHERE STATUS=0）|
|--------|------------------------------|---------------------|
| 实现方式 | 先SELECT加锁，再UPDATE，再释放锁 | 直接一条UPDATE，带条件 |
| 并发性能 | 低（等待锁释放，串行化） | 高（无等待，失败直接返回） |
| 死锁风险 | 有（忘记解锁/异常） | 无 |
| 代码复杂度 | 高（需要事务管理锁） | 低（一条SQL） |
| 适用场景 | 需要读取后再判断的复杂业务 | **状态机流转（推荐）**：0→1 |

**本系统选CAS的原因**：兑换码只需要 `0→1` 的简单状态变更，CAS一条SQL搞定，性能高、不死锁。

---

## 五、重新理解"失败锁定"：什么情况才应该锁？

### 5.1 用户的质疑完全正确 ✅

> "码存在就应该直接能用，为什么会失败？失败了锁有什么意义？"

这是一个非常好的问题。原始设计有一个**逻辑漏洞**：把三种完全不同的"失败"混为一谈，导致锁定机制意义模糊。必须把它们分清楚：

---

### 5.2 三种"失败"的本质区别

| 失败类型 | 场景 | 锁定有没有意义？ |
|---------|------|----------------|
| **A. 输入了不存在的码** | 用户打错了（把0输成O，码被截断），输入的字符串在DB里根本没有这条记录 | ❌ **没有意义** — 不存在的码根本没有记录可以锁，锁的是空气 |
| **B. 码存在但已被用/过期** | `STATUS=1`（已使用）或 `STATUS=2`（过期） | ❌ **没有意义** — 码已经是终态，再锁一次没有任何效果 |
| **C. 码存在、未用，但被多人并发抢** | 攻击者或多个用户同时提交同一个真实有效的码（STATUS=0） | ✅ **有意义** — 防止一个真实码被Bot批量账号轮流抢用 |

**结论：FAIL_COUNT 只应该在情况C下累加，情况A和B根本不应该触发锁定。**

---

### 5.3 情况A详解：输入错误（0/O混淆、印刷模糊、被截断）

```
用户输入: CP-2K5XM0-A3F7  （把1写成0）

系统处理流程:
  第1层 HMAC验证: 签名A3F7 是否匹配 CP-2K5XM0？
    → 不匹配（因为签名是根据CP-2K5XM1生成的）
    → 直接返回"无效兑换码"
    → 根本不会到达DB层，FAIL_COUNT 无从累加
```

**所以"0/O混淆输错"根本不会触发FAIL_COUNT**，因为HMAC签名校验在第1层就挡住了，连DB都不查。用户收到的是"无效兑换码"，不是"失败次数+1"。

这种情况的**真正问题**不是锁定，而是用户体验问题（用户不知道哪里输错了）。解决方案：

```java
// 前端/后端输入预处理：统一大写，替换易混字符
String normalizeCode(String input) {
    return input.toUpperCase()
                .trim()
                .replace(" ", "")     // 去空格
                .replace("0", "O")    // 如果业务上确定码里没有数字0，全转O
                // 或者反过来：如果码只用数字，把O转0
                // 取决于码生成时的字符集设计
}
```

更好的方案：**生成码时就避开易混字符**，修改 `RedeemCodeGenerator` 的Base36字符集：

```java
// 原来：BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
// 改为：去掉 0,O,I,1,L 这5个最容易混淆的字符
private static final String SAFE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
// 32个字符，安全易读，用户手输不会出错
```

---

### 5.4 情况B详解：码已被用或已过期

```
用户输入真实码 CP-2K5XM1-A3F7，但该码已经被人用了（STATUS=1）

错误的处理方式（原设计）：
  → FAIL_COUNT + 1
  → 如果累加5次 → STATUS 从1 改成3（锁定）
  → 没有任何意义，STATUS=1和STATUS=3对用户来说结果相同

正确的处理方式：
  → 直接返回"该兑换码已被使用"
  → 不修改 FAIL_COUNT，不改变 STATUS
  → 如果用户怀疑被盗用，走投诉流程
```

**修正后的服务层判断逻辑**：

```java
private Long doRedeem(String code, String userId, String clientIp, String channel) {

    CouponRedeemCode redeemCode = redeemCodeMapper.selectOne(...);

    if (redeemCode == null) {
        // 情况A：码不存在（正常情况下HMAC已在第1层拦截，理论上不会到这里）
        // 不增加FAIL_COUNT（没有记录可以加）
        throw new CouponException(404, "兑换码不存在");
    }

    // 情况B：终态判断 —— 直接返回，不加 FAIL_COUNT
    if (redeemCode.getStatus() == 1) {
        throw new CouponException(400, "该兑换码已被使用");         // ← 不加FAIL_COUNT
    }
    if (redeemCode.getStatus() == 2) {
        throw new CouponException(400, "该兑换码已过期");           // ← 不加FAIL_COUNT
    }
    if (redeemCode.getStatus() == 3) {
        throw new CouponException(400, "该兑换码已被锁定，请联系客服"); // ← 不加FAIL_COUNT
    }

    // 过期时间检查
    if (redeemCode.getExpireTime() != null && redeemCode.getExpireTime().before(new Date())) {
        // 更新为过期，不加FAIL_COUNT
        redeemCodeMapper.updateStatus(redeemCode.getId(), 2);
        throw new CouponException(400, "该兑换码已过期");
    }

    // 情况C：码有效（STATUS=0），执行CAS
    int affected = redeemCodeMapper.casRedeemCode(code, userId, new Date(), clientIp, channel);

    if (affected == 0) {
        // ✅ 只有这里才加 FAIL_COUNT
        // 原因：码是真实有效的，但被并发抢占 → 说明有人在同时争抢这个码
        redeemCodeMapper.incrementFailCount(code);
        throw new CouponException(409, "兑换失败，请稍后重试");
    }

    // 成功
    return couponService.grantCoupon(userId, userId, redeemCode.getTemplateId(), 7, "REDEEM_CODE").getId();
}
```

---

### 5.5 ❓ 既然CAS能改STATUS为1（已使用），为什么不直接用它防重？为什么还要FAIL_COUNT？

这是一个非常关键的问题，直接点出了逻辑漏洞。先把时间线画清楚：

```
正常的CAS并发场景（6个请求同时打到同一个有效码）：

t=0ms：A、B、C、D、E、F 六个请求同时到达，全部读到 STATUS=0

t=1ms：DB执行CAS，行锁保证串行：
  → 请求A：UPDATE ... WHERE STATUS=0  →  affected=1 ✅ STATUS变为1，发券成功
  → 请求B：UPDATE ... WHERE STATUS=0  →  affected=0 ❌ STATUS已经是1了
  → 请求C：UPDATE ... WHERE STATUS=0  →  affected=0 ❌
  → 请求D：UPDATE ... WHERE STATUS=0  →  affected=0 ❌
  → 请求E：UPDATE ... WHERE STATUS=0  →  affected=0 ❌
  → 请求F：UPDATE ... WHERE STATUS=0  →  affected=0 ❌

此时如果对B/C/D/E/F执行 FAIL_COUNT+1 → FAIL_COUNT=5 → 码被锁定

t=2ms：攻击者再发请求G：
  读到 STATUS = 3（锁定）
  直接返回"已被锁定"，不进入CAS ✅
```

**看起来有效——但有一个关键矛盾：**

```
t=2ms：同一个攻击者发请求G：
  读到 STATUS = 1（已使用）← 注意！A成功后STATUS就是1了！
  走"情况B"：直接返回"已被使用"，不加FAIL_COUNT

→ 码根本不需要被锁定，STATUS=1 自己就已经是终态了！
  后续任何人提交这个码都会直接被拒绝（已使用）
  FAIL_COUNT 完全没有发挥作用！
```

**结论：在"有人成功兑换"的前提下，FAIL_COUNT 没有实际作用。**  
因为 STATUS=1 本身就能拦截所有后续请求，不需要再锁定。

---

### 5.6 那FAIL_COUNT到底有没有存在价值？

**只有一个极窄的场景有意义：**

```
极端场景：攻击者拥有一个真实有效码，但目的不是自己兑换，
          而是"让真实用户无法兑换"（恶意破坏）

攻击者操作：
  用1000个Bot账号同时提交这个码，但故意不让任何一个"成功"
  （例如：请求在CAS后主动抛出异常，事务回滚，STATUS没有变成1）

结果：
  每次都是 CAS失败（affected=0）或事务回滚
  FAIL_COUNT不断累加
  到5次后，码被锁定，真实用户无法使用
```

**但这个场景在实际中几乎不存在**，因为：
1. 攻击者让CAS成功后主动回滚，技术上需要特意构造，成本极高
2. Redisson分布式锁（第4层）已经保证同一码同时只有1个线程在处理，不存在大量并发CAS失败
3. 就算1个请求失败，FAIL_COUNT=1，远未到5

**诚实的结论：FAIL_COUNT 在当前架构下意义有限**，它的存在更多是"防御纵深"的冗余设计，而非核心防刷手段。真正的核心是：

```
第1层 HMAC  → 防99%的伪造/枚举码
第5层 CAS   → 防100%的重复兑换
这两层足以保证业务正确性。FAIL_COUNT是锦上添花。
```

---

### 5.6 正确理解后的锁定机制定义

| 触发条件 | 是否加FAIL_COUNT | 原因 |
|---------|----------------|------|
| HMAC验签失败（码不合法） | ❌ 不加 | 第1层已拦截，连DB都不查 |
| 码在DB中不存在 | ❌ 不加 | 没有记录可以加 |
| 码 STATUS=1（已使用） | ❌ 不加 | 终态，后续直接拒绝，加了也没意义 |
| 码 STATUS=2（已过期） | ❌ 不加 | 终态，同上 |
| 码 STATUS=0，CAS并发失败 | ✅ 加 | 真实有效码被并发争抢（但实际上STATUS马上变1，后续不再进FAIL_COUNT） |

**简单一句话**：正常业务流程中 FAIL_COUNT 几乎不会到达5。真正的安全由第1层HMAC和第5层CAS保证。

---

### 5.7 UNLOCK_COUNT 防滥用：攻击链是什么？

> "新增 UNLOCK_COUNT 字段，超过2次解锁强制人工审核，避免攻击者反复锁定→解锁→暴力枚举循环"

这里要先弄清楚：**既然FAIL_COUNT几乎不会触发锁定，UNLOCK_COUNT还有没有意义？**

答案是：**在有自助解锁功能的前提下，UNLOCK_COUNT 防的是另一种攻击链——不是来自兑换失败，而是来自"管理员操作失误"或"客服被社工"。**

**真正需要防的攻击链如下：**

```
场景：码已经被合法用户A兑换（STATUS=1），
      攻击者B冒充用户或社工客服，让码被解锁：

攻击者B → 联系客服 → "我这个码好像没兑换成功，帮我解锁"
客服不查记录 → 把 STATUS 从 1 改回 0，FAIL_COUNT 清零
攻击者B → 立刻兑换 → 发券成功 → 真实用户A的码被盗用
```

**UNLOCK_COUNT 的意义就在这里：**

```
第1次解锁：允许（可能是正常操作失误）
第2次解锁：警告，需要客服二次确认
第3次解锁：强制人工审核，系统自动发警报
           → 客服必须查看兑换记录、IP、用户ID才能解锁
           → 防止盲目解锁导致码被盗用
```

**更重要的是：解锁必须验证"这个码从来没有被成功兑换过"**

```java
public void adminUnlock(String code, String operatorId, String reason) {
    CouponRedeemCode redeemCode = redeemCodeMapper.selectOne(...);

    // 关键检查：如果STATUS=1（已成功兑换），绝对不允许解锁！
    // 否则相当于让一个码可以被使用两次
    if (redeemCode.getStatus() == 1) {
        throw new CouponException(400,
            "该码已被用户[" + redeemCode.getUserId() + "]于["
            + redeemCode.getRedeemTime() + "]成功兑换，不可解锁。如需补偿请走补发流程。");
    }

    // 解锁次数检查
    if (redeemCode.getUnlockCount() >= 2) {
        throw new CouponException(403, "该码已被解锁2次，需要上级审批后操作");
    }

    // 执行解锁
    redeemCode.setStatus(0);
    redeemCode.setFailCount(0);
    redeemCode.setUnlockCount(redeemCode.getUnlockCount() + 1);  // 累计解锁次数
    redeemCodeMapper.updateById(redeemCode);

    log.warn("[码解锁] code={} operator={} reason={} unlockCount={}",
             code, operatorId, reason, redeemCode.getUnlockCount());
}
```

**所以 UNLOCK_COUNT 保护的是**：防止客服或管理员被骗（社工攻击），把一个"已使用的码"解锁后重复使用。

---

### 5.8 整体防护层次总结（诚实版）

| 层 | 机制 | 实际效果 | 重要程度 |
|----|------|---------|---------|
| 第1层 | HMAC签名 | 防99%枚举/伪造码 | ⭐⭐⭐⭐⭐ 核心 |
| 第2层 | IP限速 | 防单IP高频 | ⭐⭐⭐ 辅助 |
| 第3层 | 用户限速 | 防批量账号 | ⭐⭐⭐ 辅助 |
| 第4层 | 分布式锁 | 防双击并发 | ⭐⭐⭐⭐ 重要 |
| 第5层 | DB CAS | **防重复兑换的终极保证** | ⭐⭐⭐⭐⭐ 核心 |
| 第6层 | FAIL_COUNT锁定 | 极窄场景，正常流程几乎不触发 | ⭐⭐ 冗余保险 |
| 解锁保护 | UNLOCK_COUNT | 防客服被社工，防已用码被解锁重用 | ⭐⭐⭐ 运营安全 |

**一句话总结**：
- **技术安全靠第1层 + 第5层**（HMAC + CAS）
- **运营安全靠 UNLOCK_COUNT**（防管理员操作被利用）
- **第6层 FAIL_COUNT 是冗余防御**，不是核心，可以保留但不要依赖它

### 5.9 网络问题：第一次成功但前端显示失败，用户重试怎么处理？

```
t=0: 用户提交，服务端处理成功（STATUS=1），但响应在网络上丢失
t=1: 用户以为失败，再次提交同一码

第二次提交：
  进入doRedeem → getStatus()==1 → 直接返回"该码已被使用"
  ❌ 不加FAIL_COUNT（终态，正常处理）
  → 系统提示："请检查您的券包，优惠券可能已成功发放"
```

更好的方案是**前端幂等**：提交时带 `requestId`，服务端缓存结果，同一 `requestId` 返回同样的"成功"，用户不会看到"已被使用"的困惑提示。

---

## 六、六层防刷体系详解

```
用户输入码
     │
     ▼
┌──────────────────────────────────────────────────────────┐
│ 第1层：HMAC签名校验（格式层）                               │
│                                                          │
│  目的：拦截"根本不存在的假码"，不查DB                       │
│  原理：码最后4位是私钥签名，无私钥无法伪造                    │
│  效果：拦截99%的随机枚举攻击（暴力猜码）                     │
│  响应：立即返回"无效兑换码"，< 1ms                          │
│                                                          │
│  ⚠️ 注意：只防"格式无效的码"，不防"真实码被重复提交"          │
└────────────────────────┬─────────────────────────────────┘
                         │ 签名合法（码格式正确）
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 第2层：IP限速（Redis，1分钟/10次）                          │
│                                                          │
│  目的：防止单台机器/代理IP高频扫码                          │
│  原理：Redis INCR + TTL 60秒原子计数                       │
│  效果：单IP每分钟最多尝试10次，超出直接429                   │
│                                                          │
│  ⚠️ 局限：攻击者用代理池轮换IP可绕过                        │
│  补充：第1层HMAC已让代理池攻击成本极高（码无法批量伪造）       │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 第3层：用户限速（Redis，1分钟/5次）                         │
│                                                          │
│  目的：防羊毛党用批量账号反复兑换                            │
│  原理：Redis INCR + TTL 60秒，key=userId                  │
│  效果：单用户每分钟最多5次，多实例共享（Redis存储）            │
│                                                          │
│  ⚠️ 局限：攻击者用大量账号轮换可绕过（已由第1层HMAC缓解）     │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 第4层：Redisson分布式锁（并发层）                           │
│                                                          │
│  目的：防止"同一个码"被并发请求重复处理                      │
│  场景：用户双击按钮、网络超时重试、Nginx分发到不同实例         │
│  原理：tryLock(3秒等待, 5秒超时)，同一code同时只有1个线程     │
│  效果：完全防止并发重复兑换                                 │
│                                                          │
│  ⚠️ 这层是给"合法码的并发提交"设计的，不是防攻击              │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 第5层：DB CAS原子更新（数据层最终防线）                      │
│                                                          │
│  目的：绝对保证"每个码只能成功兑换一次"                      │
│  原理：UPDATE ... WHERE CODE=? AND STATUS=0               │
│        Oracle行锁保证原子性，affected=0即失败               │
│  效果：即使前4层全部失效，这层也保证不重复兑换                 │
│        + UNIQUE约束双重兜底                               │
│                                                          │
│  ✅ 这是整个体系最核心的一层，其他层都是为了减少这层的压力     │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 第6层：失败次数锁定（惩罚层）                               │
│                                                          │
│  目的：防止对单个真实码的持续暴力重试                        │
│  原理：每次CAS失败/状态不对 → FAIL_COUNT+1                 │
│        FAIL_COUNT >= 5 → STATUS=3（锁定）                 │
│  效果：攻击者即使知道码存在，也无法无限重试                   │
│                                                          │
│  ⚠️ 副作用：合法用户也可能被锁定（见第五章：解锁处理方案）     │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
                   发放优惠券 ✅
```

### 6.1 各层针对的攻击类型

| 攻击者行为 | 被哪层拦截 |
|-----------|---------|
| 随机生成码枚举 | 第1层（HMAC签名不合法，直接拒绝） |
| 单IP高频尝试 | 第2层（IP限速） |
| 批量账号扫码 | 第3层（用户限速）+ 第1层（无效码） |
| 双击/网络重试并发 | 第4层（分布式锁） |
| 极端并发绕过锁 | 第5层（DB CAS，终极保证） |
| 持续尝试某个真实码 | 第6层（失败锁定） |

---

## 七、DB表设计（已加入 schema.sql）

```sql
-- 批次表
CREATE TABLE T_REDEEM_CODE_BATCH (
    ID              NUMBER(19)    PRIMARY KEY,
    BATCH_NAME      VARCHAR2(100) NOT NULL,
    TEMPLATE_ID     NUMBER(19)    NOT NULL,
    TOTAL_COUNT     NUMBER(10)    NOT NULL,
    REDEEMED_COUNT  NUMBER(10)    DEFAULT 0,
    STATUS          NUMBER(1)     DEFAULT 1,  -- 0生成中 1激活 2停用 3过期
    EXPIRE_TIME     TIMESTAMP     NOT NULL,
    CREATE_BY       VARCHAR2(50),
    REMARK          VARCHAR2(500),
    CREATE_TIME     TIMESTAMP     DEFAULT SYSTIMESTAMP,
    UPDATE_TIME     TIMESTAMP     DEFAULT SYSTIMESTAMP,
    DELETED         NUMBER(1)     DEFAULT 0
);

-- 码实例表
CREATE TABLE T_COUPON_REDEEM_CODE (
    ID              NUMBER(19)    PRIMARY KEY,
    CODE            VARCHAR2(32)  NOT NULL,    -- CP-2K5XM1-A3F7
    BATCH_ID        NUMBER(19)    NOT NULL,
    TEMPLATE_ID     NUMBER(19)    NOT NULL,
    STATUS          NUMBER(1)     DEFAULT 0,   -- 0未用 1已用 2过期 3锁定
    USER_ID         VARCHAR2(50),
    REDEEM_TIME     TIMESTAMP,
    REDEEM_IP       VARCHAR2(50),              -- 审计用
    REDEEM_CHANNEL  VARCHAR2(20),              -- APP/WEB/H5/SMS
    FAIL_COUNT      NUMBER(5)     DEFAULT 0,   -- 失败次数，>=5→锁定
    UNLOCK_COUNT    NUMBER(3)     DEFAULT 0,   -- 已解锁次数，>=2→强制人工
    EXPIRE_TIME     TIMESTAMP     NOT NULL,
    CREATE_TIME     TIMESTAMP     DEFAULT SYSTIMESTAMP,
    UPDATE_TIME     TIMESTAMP     DEFAULT SYSTIMESTAMP,
    DELETED         NUMBER(1)     DEFAULT 0,
    CONSTRAINT UK_REDEEM_CODE UNIQUE (CODE)
);

CREATE INDEX IDX_RDCODE_BATCH   ON T_COUPON_REDEEM_CODE(BATCH_ID);
CREATE INDEX IDX_RDCODE_STATUS  ON T_COUPON_REDEEM_CODE(STATUS);
CREATE INDEX IDX_RDCODE_USER    ON T_COUPON_REDEEM_CODE(USER_ID);
CREATE INDEX IDX_RDCODE_EXPIRE  ON T_COUPON_REDEEM_CODE(EXPIRE_TIME);
```

**关键字段说明**：
- `UNLOCK_COUNT`：新增，记录该码被解锁次数，超过2次强制人工审核，防止攻击者反复锁定→解锁→再枚举

---

## 八、API接口

| 方法 | 路径 | 说明 | 调用方 |
|------|------|------|--------|
| POST | /api/redeem-code/batch/generate | 批量生成码 | 管理后台 |
| POST | /api/redeem-code/redeem | 用户兑换 | APP/H5 |
| POST | /api/redeem-code/unlock | 用户自助解锁 | APP |
| POST | /api/redeem-code/admin/unlock | 客服人工解锁 | 客服后台 |
| GET  | /api/redeem-code/batch/{id} | 查询批次统计 | 管理后台 |

---

## 九、部署 Checklist

### 上线前必做

- [ ] IntelliJ → pom.xml右键 → **Maven → Reload Project**（下载Druid依赖）
- [ ] 执行 schema.sql 新增的两张表 + 索引（包含新增 `UNLOCK_COUNT` 字段）
- [ ] **修改HMAC密钥**（生产必须改！）`coupon.code.redeem.hmac-secret: 至少32位`
- [ ] Druid控制台密码改为强密码，`allow` 配置为内网IP段

### 多实例注意

- [ ] 三实例使用**相同HMAC密钥**（K8s Secret / Nacos 统一注入）
- [ ] Nginx 使用**轮询模式**，不配 ip_hash
- [ ] Redis 使用**集群/哨兵模式**，单点故障会导致限速失效
- [ ] 解锁接口需配置权限（自助解锁 vs 客服解锁 分开鉴权）
````
