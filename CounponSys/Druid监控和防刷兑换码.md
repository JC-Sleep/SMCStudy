# 2026-04-17 两大功能实现总结

---

## 一、Druid数据库连接监控（为什么选Druid，不自己写）

### HikariCP（原来）vs Druid（现在）对比

| 能力 | HikariCP | Druid |
|------|----------|-------|
| 性能 | ⭐⭐⭐⭐⭐ 最快 | ⭐⭐⭐⭐ 略慢（约3%） |
| Web监控控制台 | ❌ 无 | ✅ /druid 内置 |
| 慢SQL统计 | ❌ 无 | ✅ slowSqlMillis=2000 |
| **长连接泄漏检测** | 有但弱 | ✅ **打印调用栈**（定位神器！） |
| SQL防注入（Wall） | ❌ 无 | ✅ 内置Wall Filter |
| 连接等待告警 | ❌ 无 | ✅ waitThreadCount监控 |

**结论**：电讯行业系统怕长连接卡死 → Druid的`remove-abandoned=true` + `log-abandoned=true`直接打印是哪行代码拿了连接没还，排查速度↑10倍

### 访问方式
- **Web控制台**: http://localhost:8090/druid  (admin/admin123)
- **SQL监控页**: http://localhost:8090/druid/sql.html
- **监控API**:   GET /api/monitor/db

### 关键配置（application.yml）
```yaml
druid:
  remove-abandoned: true       # 启用泄漏检测
  remove-abandoned-timeout: 180  # 3分钟未归还=泄漏
  log-abandoned: true          # ★ 打印泄漏连接的完整调用栈！
  connection-properties: druid.stat.slowSqlMillis=2000  # 慢SQL阈值2秒
  filters: stat,wall,slf4j     # SQL统计+防注入+日志
```

### 新增文件
| 文件 | 作用 |
|------|------|
| `monitor/DruidConnectionMonitor.java` | 每30秒打印连接池状态、高水位告警 |
| `controller/DbMonitorController.java` | REST API暴露连接池实时状态 |
| `pom.xml` | 新增druid-spring-boot-starter 1.2.20 + actuator |

---

## 二、兑换码生成与防刷兑换

### 码格式设计
```
CP-2K5XM1-A3F7
│  │      └── HMAC-SHA256签名取前4位（大写）
│  └────────── Base36编码的DB主键ID
└──────────── 固定前缀
```
**为什么HMAC不可枚举**：签名由服务端私钥计算，攻击者枚举1亿个ID，猜对签名概率=1/36⁴≈0.00006%。再叠加限速，实际为0。

### 六层防刷体系

```
用户请求
    │
    ▼
[第1层] HMAC签名校验 → 格式无效直接拒绝，不查DB
    │
    ▼
[第2层] IP限速：同IP每分钟最多10次
    │
    ▼
[第3层] 用户限速：同用户每分钟最多5次
    │
    ▼
[第4层] Redisson分布式锁：同一code同时只允许1个线程
    │
    ▼
[第5层] DB CAS原子更新：WHERE CODE=? AND STATUS=0
         返回0→已被抢先兑换，返回1→成功
    │
    ▼
[第6层] 失败次数锁定：同一码失败5次自动锁定
```

### DB表设计（Oracle DDL已加入schema.sql）

**T_REDEEM_CODE_BATCH**（批次表）
- 每次运营活动生成一批码 = 一条批次记录
- 追踪 total_count / redeemed_count

**T_COUPON_REDEEM_CODE**（码实例表）
```sql
CODE            VARCHAR2(32)  UNIQUE  -- HMAC签名码
STATUS          NUMBER(1)           -- 0未用 1已用 2过期 3锁定
FAIL_COUNT      NUMBER(5)           -- 失败次数，超5次→STATUS=3
REDEEM_IP       VARCHAR2(50)        -- 兑换来源IP（审计用）
REDEEM_CHANNEL  VARCHAR2(20)        -- APP/WEB/H5
```

### 新增文件
| 文件 | 作用 |
|------|------|
| `entity/CouponRedeemCode.java` | 兑换码实体 |
| `entity/RedeemCodeBatch.java` | 批次实体 |
| `mapper/CouponRedeemCodeMapper.java` | CAS原子更新、批量插入 |
| `mapper/RedeemCodeBatchMapper.java` | 批次CRUD |
| `resources/mapper/CouponRedeemCodeMapper.xml` | Oracle批量INSERT ALL语法 |
| `util/RedeemCodeGenerator.java` | HMAC-SHA256码生成+校验 |
| `service/impl/RedeemCodeServiceImpl.java` | 完整防刷兑换逻辑 |
| `controller/RedeemCodeController.java` | REST API |
| `resources/db/schema.sql` | 新增两张表DDL+索引 |

### API接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/redeem-code/batch/generate | 后台批量生成码 |
| POST | /api/redeem-code/redeem | 用户兑换 |
| GET  | /api/redeem-code/batch/{batchId} | 查询批次 |
| GET  | /api/redeem-code/batch/{batchId}/codes | 查询批次下所有码 |

### 生成兑换码示例（请求体）
```json
POST /api/redeem-code/batch/generate
{
  "templateId": 1001,
  "count": 1000,
  "expireTime": "2026-12-31T23:59:59",
  "batchName": "2026双十一活动",
  "operatorId": "admin"
}
```

### 兑换示例（用户端）
```json
POST /api/redeem-code/redeem
{
  "code": "CP-2K5XM1-A3F7",
  "userId": "user123",
  "channel": "APP"
}
```

---

## 操作步骤

1. **IntelliJ → pom.xml右键 → Maven → Reload Project**（下载Druid依赖）
2. **执行schema.sql新增的DDL**（两张新表）
3. **修改application.yml中的HMAC密钥**（生产必须改！）：
   ```yaml
   coupon.code.redeem.hmac-secret: 你的强密钥
   ```
4. **Druid Web控制台**：http://localhost:8090/druid（admin/admin123，生产改密码）
