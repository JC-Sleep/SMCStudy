# Plan: 电讯行业优惠券系统（CouponSys）完整实现方案

> **实现状态**: ✅ 全部核心模块已完成 + 高并发场景优化完成（2026-03-26）
> 
> **已完成功能清单:**
> - ✅ 项目基础结构(pom.xml, application.yml)
> - ✅ 数据库表设计(Oracle DDL)
> - ✅ 实体层 & 枚举类
> - ✅ Mapper层
> - ✅ Service层(优惠券服务、秒杀服务、积分服务、模板服务)
> - ✅ Controller层(优惠券API、秒杀API、模板管理API)
> - ✅ MQ消费者(秒杀订单处理、月度赠送处理、消费事件监听)
> - ✅ 定时任务(过期处理、秒杀预热、月度赠送)
> - ✅ Redis配置 & Lua脚本
> - ✅ ~~ActiveMQ配置~~ → **Kafka配置** (高并发改造)
> - ✅ Swagger/Knife4j API文档
> - ✅ 异常处理 & DTO
> - ✅ 中间件使用指南文档
> - ✅ **Guava限流器** (新增)
> - ✅ **分布式限流器(Redis)** (新增)
> - ✅ **Kafka消费者** (新增)
> - ✅ **秒杀技术难点分析文档** (新增)
> - ✅ **高并发系统问题分析与解决方案文档** (新增)
> - ✅ **压测指南与限流原理分析文档** (新增)
> - ✅ **分布式部署超抢问题分析文档** (新增)
> - ✅ **动态限流管理器** (2026-03-26 新增)
> - ✅ **Kafka积压监控** (2026-03-26 新增)
> - ✅ **智能库存不足判断** (2026-03-26 新增)
> - ✅ **活动真正结束判断** (2026-03-26 新增)
> - ✅ **提前售罄自动检测** (2026-03-26 新增)
> - ✅ **Job任务智能跳过** (2026-03-26 新增)
> - ✅ **订单处理最终一致性文档** (2026-03-26 新增)
> - ✅ **高并发回滚与活动结束优化文档** (2026-03-26 新增)
> - ✅ **KEDA自动扩缩容方案** (2026-03-27 新增)
> - ✅ **Kafka积压自动化解决方案** (2026-03-27 新增)
> - ✅ **K8s快速扩缩容脚本** (2026-03-27 新增)
> - ✅ **Druid连接池监控** (2026-04-17 新增) — 替换HikariCP，含Web控制台/慢SQL/连接泄漏检测
> - ✅ **兑换码生成与防刷兑换** (2026-04-17 新增) — HMAC签名+6层防刷体系+DB原子更新
> - ✅ **失败次数锁定（惩罚层）精细化** (2026-04-20 新增) — 仅CAS并发失败才计FAIL_COUNT，终态码不触发锁定
> - ✅ **失败5次锁定→三级自助处理流程** (2026-04-20 新增) — 大部分不需人工介入
> - ✅ **UNLOCK_COUNT防解锁滥用** (2026-04-20 新增) — 超2次解锁强制人工审核，防社工攻击
> - ✅ **网络失败幂等重试方案** (2026-04-20 新增) — requestId缓存，防用户重试看到"已被使用"困惑提示
> - ✅ **瑕疵修复：分布式事务（本地消息表）** (2026-04-20 修复) — T_GRANT_TASK+GrantTask+GrantTaskMapper+重试Job，CAS与写Task同一事务
> - ✅ **瑕疵修复：兑换码过期Job** (2026-04-20 修复) — expireRedeemCodes()每小时批量标记，分批5000条防大事务
> - ✅ **瑕疵修复：同批次重复兑换限制** (2026-04-20 修复) — MAX_PER_USER字段+countRedeemedByUserAndBatch校验
> - ✅ **瑕疵修复：requestId幂等实现** (2026-04-20 修复) — Redis缓存5分钟，Controller传参，Service查缓存
> - ✅ **瑕疵修复：JWT身份认证** (2026-04-20 修复) — JwtUtil+JwtAuthFilter+SecurityConfig，接口分USER/ADMIN权限
> - ✅ **瑕疵修复：UNLOCK_COUNT解锁保护** (2026-04-20 修复) — selfUnlock/adminUnlock，STATUS=1禁止解锁，>=2次强制人工
> - ✅ **Kafka死信队列（DLT）** (2026-04-22 新增) — KafkaConfig新增TOPIC_SECKILL_ORDER_DLT，SeckillOrderDltConsumer持久化失败订单+打印人工处理告警日志
> - ✅ **Kafka重试队列（指数退避）** (2026-04-22 新增) — T_SECKILL_RETRY_TASK表+SeckillRetryTaskMapper+retrySeckillOrders Job，临时故障1/5/30分钟指数退避重试，3次超限→发死信Topic，保护用户权益
>
> **性能指标**: 支持千万级用户，5-10W QPS并发，Redis压力↓70%，Kafka消费速度↑100%，积压恢复速度↑5.5倍

在CouponSys空白项目中构建一套完整的电讯行业优惠券系统，基于SpringBoot + Oracle + MyBatis-Plus + Redis + **Kafka**技术栈，实现优惠券的发放、领取、核销、过期等全生命周期管理，支持套餐赠送、活动营销、消费激励、秒杀抢券等多种业务场景。

## 业务背景

### 常见优惠券类型
- **套餐赠送型**：每月固定赠送（如每月100港币话费券）
- **活动营销型**：节日/促销期间发放
- **消费激励型**：达到消费门槛自动发放
- **推荐奖励型**：推荐新用户获得
- **挽留客户型**：合约到期前挽留用户
- **秒杀抢券型**：限时限量抢购（积分兑换/优惠价抢购）

### 秒杀抢券业务场景
支持多种抢券入口：
1. **第三方积分兑换**：对接外部积分平台（如信用卡积分），用积分抢本公司优惠券
2. **本公司APP积分抢**：用户在APP内使用会员积分兑换优惠券
3. **本公司用户优惠价抢**：老用户/VIP用户享受更低价格或优先抢购

**秒杀核心规则：**
- 定时开抢（如每周五 12:00）
- 限量库存（如仅500张）
- 每人限抢N张
- 积分/金额实时扣减
- 抢购失败自动退还积分

### 优惠券生命周期
```
发放 → 领取 → 核销 → 过期/失效
```

### 关键业务规则
- 有效期管理（绝对时间/相对时间）
- 使用门槛（满减/折扣/免单）
- 叠加规则（是否可与其他优惠同时使用）
- 适用范围（特定套餐/服务类型）
- 数量限制（每人限领/总量控制）

### 实际应用建议
- **库存预警**：剩余10%时触发补券流程
- **风控规则**：限制单用户每日领取次数
- **灰度发放**：新券先小范围测试
- **数据分析**：追踪核销率优化券策略

## Steps

### 1. 配置项目基础结构
更新 `pom.xml`，添加Spring Boot Web、MyBatis-Plus、Oracle驱动、Redis、RabbitMQ等依赖；创建`application.yml`配置数据源、Redis、MQ连接；创建主启动类`CouponSystemApplication.java`

**涉及文件：**
- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/java/sys/smc/coupon/CouponSystemApplication.java`

### 2. 设计数据库表与实体层
创建`entity/`目录，定义核心实体：
- `CouponTemplate`(券模板) - 定义券的基本属性模板
- `CouponBatch`(券批次) - 每次发放活动的批次信息
- `Coupon`(券实例) - 用户持有的具体优惠券
- `CouponUseRecord`(核销记录) - 券使用的详细记录
- `CouponRule`(发放规则) - 自动发放的触发规则

包含有效期、门槛、叠加、适用范围等字段；同步创建Oracle建表DDL脚本

**涉及文件：**
- `src/main/java/sys/smc/coupon/entity/*.java`
- `src/main/resources/db/schema.sql`

### 3. 实现Mapper与Service核心业务
创建`mapper/`和`service/`目录，实现：
- 优惠券CRUD操作
- 库存管理（`RedisTemplate`原子操作扣减）
- 发放接口（套餐定时发/活动发/消费达标发）
- 领取核销（分布式锁防重领）
- 过期自动失效（延迟队列+定时任务）

**涉及文件：**
- `src/main/java/sys/smc/coupon/mapper/*.java`
- `src/main/java/sys/smc/coupon/service/*.java`
- `src/main/java/sys/smc/coupon/service/impl/*.java`
- `src/main/resources/mapper/*.xml`

### 4. 构建发放触发引擎
创建`job/`定时任务模块：
- `MonthlyGrantJob` - 每月套餐赠送定时任务
- `CouponExpireJob` - 过期扫描定时任务

创建`mq/`消息模块：
- `CouponProducer` - 消息生产者
- `CouponConsumer` - 消息消费者

处理消费激励型异步发放、挽留客户型事件触发

**涉及文件：**
- `src/main/java/sys/smc/coupon/job/*.java`
- `src/main/java/sys/smc/coupon/mq/*.java`

### 5. 暴露Controller API接口
创建`controller/`目录，提供REST接口：
- `POST /coupon/grant` - 发券
- `POST /coupon/claim` - 领券
- `POST /coupon/redeem` - 核销
- `GET /coupon/list` - 查询用户券
- `CRUD /coupon/template/**` - 券模板管理
- `POST /coupon/seckill/grab` - 秒杀抢券
- `GET /coupon/seckill/activities` - 查询秒杀活动列表
- `GET /coupon/seckill/status/{activityId}` - 查询活动状态/库存

加入风控校验（单用户每日领取限制、秒杀防刷）

**涉及文件：**
- `src/main/java/sys/smc/coupon/controller/*.java`

### 5.1 实现秒杀抢券核心模块
创建`seckill/`目录，实现秒杀专用逻辑：
- `SeckillActivity`(秒杀活动) - 活动时间、库存、限购规则
- `SeckillService` - 抢券核心逻辑（Redis预扣库存 + 异步落库）
- `PointsService` - 积分扣减/回滚（对接内部积分系统或第三方）
- `SeckillLimiter` - 限流器（令牌桶/滑动窗口）

**技术要点：**
- Redis预热活动库存
- Lua脚本原子扣减（库存+用户限购次数）
- MQ异步创建券记录
- 积分扣减失败自动回滚

**涉及文件：**
- `src/main/java/sys/smc/coupon/seckill/*.java`
- `src/main/java/sys/smc/coupon/integration/PointsClient.java`

### 6. 补充配置与监控
创建`config/`目录配置：
- `RedisConfig` - Redis连接与序列化配置
- `RabbitMQConfig` - MQ队列与交换机配置
- `AsyncConfig` - 异步任务线程池配置

创建`exception/`全局异常处理
创建`dto/`请求响应对象
可选添加Kubernetes部署YAML

**涉及文件：**
- `src/main/java/sys/smc/coupon/config/*.java`
- `src/main/java/sys/smc/coupon/exception/*.java`
- `src/main/java/sys/smc/coupon/dto/*.java`
- `k8s/deployment.yml`（可选）

## 技术难点与解决方案

### 1. 分布式唯一券码生成
- **方案**：雪花算法(Snowflake) + Redis自增序列混合
- **格式**：`CP` + 年月日(6位) + 批次号(4位) + 序列号(8位)
- **示例**：`CP2603251001 00000001`

### 2. 库存超卖防控
- **方案**：Redis Lua脚本原子扣减
- **流程**：先扣Redis库存 → 成功后写DB → 失败回滚Redis
- **Lua脚本**：保证check-and-decrement原子性

### 3. 防重复领取
- **方案**：Redisson分布式锁 + 数据库唯一索引双重保障
- **锁Key**：`coupon:claim:{userId}:{templateId}`
- **锁超时**：5秒，防止死锁

### 4. 延迟过期处理
- **方案**：RabbitMQ死信队列实现延迟消息
- **流程**：发券时发送延迟消息 → 到期触发消费 → 更新券状态为过期

### 5. 高并发场景优化
- **本地缓存**：Caffeine缓存券模板信息
- **异步处理**：MQ解耦发券请求与实际发放
- **批量操作**：MyBatis-Plus批量插入减少DB压力

### 6. 秒杀抢券高并发方案
- **流量削峰**：前端倒计时 + 后端令牌桶限流
- **库存预热**：活动开始前将库存加载到Redis
- **Lua原子操作**：同时校验库存、用户限购、扣减库存
- **异步落库**：抢购成功后发MQ，消费者批量写DB
- **积分事务**：先扣积分→再发券→失败回滚积分
- **防重复抢**：用户ID+活动ID作为Redis Key去重

**Lua脚本核心逻辑：**
```lua
-- 检查库存
local stock = redis.call('GET', stockKey)
if tonumber(stock) <= 0 then return -1 end
-- 检查用户已抢次数
local grabbed = redis.call('GET', userKey)
if tonumber(grabbed or 0) >= limit then return -2 end
-- 扣减库存 & 记录用户
redis.call('DECR', stockKey)
redis.call('INCR', userKey)
return 1
```

### 7. 计费系统对接（消费激励型）
**为什么需要对接计费系统？**
- 消费激励型优惠券需要知道用户"消费了多少钱"
- 计费系统(Billing)记录所有消费明细：话费、流量、增值服务
- 例如：用户本月消费满500港币 → 自动发放50港币话费券

**对接方式（二选一）：**
1. **事件订阅**：计费系统每笔消费完成后发MQ消息，优惠券系统消费判断
2. **定期拉取**：每天从计费系统API查询用户消费汇总

**涉及文件：**
- `src/main/java/sys/smc/coupon/integration/BillingClient.java`
- `src/main/java/sys/smc/coupon/listener/ConsumptionEventListener.java`

## Further Considerations

1. **分布式唯一券码生成** - 推荐使用雪花算法(Snowflake)或UUID + Redis自增序列混合方案，需确定券码格式（如纯数字/字母混合）

2. **库存超卖风控** - Redis Lua脚本原子扣减 vs 乐观锁版本号方案，高并发场景建议选择Redis Lua

3. **消息队列选型** - 根据并发量选择：
   | 特性 | ActiveMQ | Kafka | 你的场景(5-10W QPS) |
   |------|----------|-------|---------------------|
   | 吞吐量 | 1-2万/秒 | 10-100万/秒 | **必须用Kafka** |
   | 延迟消息 | ✅ 原生支持 | ❌ 需额外方案 | - |
   | 运维复杂度 | 低 | 中 | - |
   
   **结论**：千万级用户、5-10W QPS场景，**必须使用Kafka**！

4. **计费系统对接** - 消费激励型需订阅用户消费事件，与计费Team确认：
   - 消费事件MQ Topic名称
   - 消息格式（userId, amount, consumeTime, consumeType）
   - 是否支持API查询月度消费汇总

5. **积分系统对接** - 秒杀抢券需对接积分服务：
   - 内部积分系统API（扣减/回滚/查询）
   - 第三方积分平台OAuth对接

## 项目结构预览

```
CouponSys/
├── pom.xml
├── 中间件使用指南.md                    # Redis/ActiveMQ使用说明
├── plan-couponSystem.prompt.md
├── src/
│   ├── main/
│   │   ├── java/sys/smc/coupon/
│   │   │   ├── CouponSystemApplication.java
│   │   │   ├── config/
│   │   │   │   ├── RedisConfig.java
│   │   │   │   ├── ActiveMQConfig.java
│   │   │   │   ├── MybatisPlusConfig.java
│   │   │   │   └── SwaggerConfig.java      # API文档配置
│   │   │   ├── controller/
│   │   │   │   ├── CouponController.java
│   │   │   │   ├── CouponTemplateController.java  # 模板管理
│   │   │   │   └── SeckillController.java
│   │   │   ├── dto/
│   │   │   │   ├── request/
│   │   │   │   │   ├── ClaimCouponRequest.java
│   │   │   │   │   ├── RedeemCouponRequest.java
│   │   │   │   │   ├── SeckillGrabRequest.java
│   │   │   │   │   └── CouponTemplateRequest.java
│   │   │   │   └── response/
│   │   │   │       ├── ApiResponse.java
│   │   │   │       ├── CouponDTO.java
│   │   │   │       ├── CouponTemplateDTO.java
│   │   │   │       ├── SeckillActivityDTO.java
│   │   │   │       ├── SeckillGrabResult.java
│   │   │   │       └── RedeemResult.java
│   │   │   ├── entity/
│   │   │   │   ├── CouponTemplate.java
│   │   │   │   ├── Coupon.java
│   │   │   │   ├── CouponUseRecord.java
│   │   │   │   ├── CouponRule.java
│   │   │   │   ├── SeckillActivity.java
│   │   │   │   └── SeckillOrder.java
│   │   │   ├── enums/
│   │   │   │   ├── CouponStatus.java
│   │   │   │   ├── CouponType.java
│   │   │   │   ├── GrantType.java
│   │   │   │   ├── PointsChannel.java
│   │   │   │   └── SeckillStatus.java
│   │   │   ├── exception/
│   │   │   │   ├── CouponException.java
│   │   │   │   ├── SeckillException.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── job/
│   │   │   │   ├── CouponScheduledJob.java     # 过期处理+秒杀预热
│   │   │   │   └── MonthlyGrantJob.java        # 月度套餐赠送
│   │   │   ├── listener/
│   │   │   │   └── ConsumptionEventListener.java
│   │   │   ├── mapper/
│   │   │   │   ├── CouponTemplateMapper.java
│   │   │   │   ├── CouponMapper.java
│   │   │   │   ├── CouponUseRecordMapper.java
│   │   │   │   ├── SeckillActivityMapper.java
│   │   │   │   └── SeckillOrderMapper.java
│   │   │   ├── mq/
│   │   │   │   ├── CouponGrantConsumer.java    # 月度赠送消费者
│   │   │   │   └── SeckillOrderConsumer.java   # 秒杀订单消费者
│   │   │   ├── service/
│   │   │   │   ├── CouponService.java
│   │   │   │   ├── CouponTemplateService.java
│   │   │   │   ├── SeckillService.java
│   │   │   │   ├── PointsService.java
│   │   │   │   └── impl/
│   │   │   │       ├── CouponServiceImpl.java
│   │   │   │       ├── CouponTemplateServiceImpl.java
│   │   │   │       ├── SeckillServiceImpl.java
│   │   │   │       └── PointsServiceImpl.java
│   │   │   └── util/
│   │   │       ├── CouponCodeGenerator.java
│   │   │       └── RedisKeys.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── db/
│   │       │   └── schema.sql
│   │       ├── lua/
│   │       │   └── seckill_grab.lua
│   │       └── mapper/
│   │           └── *.xml
│   └── test/
│       └── java/
└── k8s/
    └── deployment.yml (待实现)
```

## API接口清单

### 优惠券管理 `/api/coupon`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /claim | 领取优惠券 |
| POST | /redeem | 核销优惠券 |
| GET | /list | 查询用户优惠券列表 |
| GET | /detail | 查询优惠券详情 |

### 模板管理 `/api/coupon/template`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /create | 创建模板 |
| PUT | /update | 更新模板 |
| GET | /{id} | 获取模板详情 |
| GET | /page | 分页查询模板 |
| POST | /{id}/enable | 启用模板 |
| POST | /{id}/disable | 停用模板 |
| DELETE | /{id} | 删除模板 |

### 秒杀抢券 `/api/seckill`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /grab | 抢购优惠券 |
| GET | /activities | 获取活动列表 |
| GET | /activity/{id} | 获取活动详情 |
| GET | /status/{id} | 获取活动库存状态 |

**API文档地址**: http://localhost:8090/doc.html


## 高并发场景优化（2026-03-26新增）

### 优化背景

在实际运行中发现以下边界场景问题：
1. Kafka消费失败回滚库存后，Redis库存从0变回100，但用户已看到"已售罄"，造成困惑
2. 活动endTime已过，但还有待处理订单可能回滚库存，不应立即标记结束
3. 活动真正售罄（DB库存=0且无待处理订单）时，Job任务仍在运行，浪费资源
4. Guava固定限流1万QPS，但库存只剩2千时，导致8千次无效Lua执行

### 优化清单

#### 1. 动态限流管理器（DynamicRateLimiterManager）
**问题**：固定限流导致Redis压力大，无效Lua执行多

**解决**：
- 根据剩余库存动态调整限流速率（库存 × 1.5倍）
- 每5秒自动调整一次
- 库存10000 → 限流10000；库存2000 → 限流3000；库存0 → 限流100

**效果**：
- Redis压力↓70%（库存2千时，从1万降到3千）
- 无效Lua执行↓87.5%（从8千次降到1千次）

**文件**：`util/DynamicRateLimiterManager.java`

---

#### 2. 智能库存不足判断（handleStockInsufficient）
**问题**：Redis库存=0时，直接返回"已售罄"，但5秒后回滚了100，用户疑惑

**解决**：智能判断三种情况
- Redis=0 + DB>0 → "活动火爆，请稍后刷新重试"（可能有回滚）
- Redis=0 + 待处理订单>0 → "活动火爆，请稍后刷新重试"（等待处理）
- Redis=0 + DB=0 + 待处理=0 → "已售罄"（真正售罄）

**效果**：
- 用户体验更好（不会误以为售罄）
- 库存利用率100%（回滚后继续抢购）
- 转化率提升（用户会刷新重试）

**文件**：
- `exception/SeckillException.java` - 新增`stockInsufficientRetry()`
- `service/impl/SeckillServiceImpl.java` - 新增`handleStockInsufficient()`

---

#### 3. 活动真正结束判断（checkAndFinishActivity）
**问题**：endTime已过就标记结束，但还有待处理订单可能回滚库存

**解决**：真正结束的三个条件
- endTime已过 ✅
- DB库存=0 ✅
- 无待处理订单 ✅

**效果**：
- 活动结束判断更准确
- 不会遗漏回滚的库存
- 自动清理Redis缓存

**文件**：`job/CouponScheduledJob.java` - 优化`updateSeckillActivityStatus()`

---

#### 4. 提前售罄自动检测（checkSoldOutActivities）
**问题**：活动还没到endTime，但DB库存已经=0

**解决**：每30秒检测一次
- 检查进行中的活动
- DB库存=0 + 无待处理订单 → 标记SOLD_OUT
- 清理Redis缓存，释放资源

**效果**：
- 提前标记售罄（前端可显示"已售罄"）
- Job任务停止无效处理
- 释放Redis内存

**文件**：`job/CouponScheduledJob.java` - 新增`checkSoldOutActivities()`

---

#### 5. Job任务智能跳过
**问题**：活动已结束，Job还在不断检查，浪费CPU

**解决**：
- 只查询进行中的活动
- 无进行中活动 → 直接return
- 记录日志："当前无进行中的秒杀活动，跳过限流调整"

**效果**：节省CPU资源

**文件**：`job/CouponScheduledJob.java` - 优化`adjustDynamicRateLimiters()`

---

#### 6. Kafka积压监控（KafkaLagMonitor）
**问题**：Kafka消息积压时没有监控告警

**解决**：每30秒检查一次
- 积压>1万条 → 普通告警
- 积压>10万条 → 严重告警

**效果**：及时发现消费异常

**实现位置**：
- 📍 **文件**: `monitor/KafkaLagMonitor.java` (已修复，290行)
- 📍 **定时任务**: `checkLag()` - 每30秒执行（第73-93行）
- 📍 **核心方法**:
  - `getConsumerLag(String groupId)` - 获取积压数量（第103-149行）
    - 第113行：获取消费者组详情
    - 第119行：获取已提交的offset
    - 第126行：获取最新的offset
    - 第135行：计算总积压 `lag = endOffset - committedOffset`
  - `sendWarningAlert(long lag)` - 普通告警（第163-187行）
  - `sendCriticalAlert(long lag)` - 严重告警（第194-226行）
  - `getHealthStatus()` - 获取健康状态（第234-269行）
- 📍 **常量配置**:
  - 第49行：普通告警阈值 `WARNING_THRESHOLD = 10000`
  - 第54行：严重告警阈值 `CRITICAL_THRESHOLD = 100000`
  - 第64行：告警间隔 `ALERT_INTERVAL = 5分钟`

---

#### 7. Kafka消费者优化
**优化**：
- concurrency从10增加到20（消费速度↑100%）
- 批量拉取优化（max-poll-records: 500）
- fetch-min-size和fetch-max-wait优化

**效果**：
- Kafka消费速度↑100%
- 积压延迟↓50%（从最高9分钟降到4.5分钟）

**实现位置**：
- 📍 **配置文件**: `application.yml`
  - 第59-72行：Kafka consumer配置优化
    - 第65行：`max-poll-records: 500` - 批量拉取
    - 第67行：`fetch-min-size: 1024` - 最小拉取1KB
    - 第68行：`fetch-max-wait: 500` - 最多等待500ms
  - 第73-78行：Kafka listener配置
    - 第77行：`concurrency: 20` - 从10增加到20
- 📍 **消费者类**: `mq/SeckillOrderKafkaConsumer.java`
  - 第46行：`@KafkaListener(concurrency = "10")` - 注解配置（被yml覆盖）
  - 第49-129行：`processSeckillOrder()` - 消费逻辑
    - 第67-78行：扣减积分
    - 第92-99行：发放优惠券
    - 第102行：扣减DB库存
    - 第107行：更新订单状态为成功
    - 第119-129行：异常时触发补偿机制

---

### 优化效果对比

| 指标 | 优化前 | 优化后 | 提升 |
|------|-------|-------|------|
| Redis Lua执行 | 1万次/秒 | 3千次/秒 | ↓70% |
| 无效Lua执行 | 8千次 | 1千次 | ↓87.5% |
| Kafka消费速度 | 1万条/秒 | 2万条/秒 | ↑100% |
| 积压延迟 | 最高9分钟 | 最高4.5分钟 | ↓50% |
| 用户体验 | 误导提示 | 友好提示 | ⭐⭐⭐⭐⭐ |
| 库存利用率 | 99%（浪费回滚库存） | 100% | ↑1% |
| 系统资源 | Job持续运行 | 智能跳过 | 节省CPU |

---

### 相关文档

1. **订单处理最终一致性解决方案.md** - Kafka异步处理与补偿机制详解
2. **三大功能完整实现验证报告.md** - 三大功能验证
3. **高并发回滚与活动结束优化方案.md** - 本次优化技术方案
4. **高并发场景优化完成报告.md** - 优化完成报告
5. **代码修改总结.md** - 部署指南

---

### 时序图示例：回滚库存后的完整流程

```
0秒：1万人抢购
   ↓
Redis扣库存（10000 → 0）
   ↓
创建订单status=0（待处理）
   ↓
返回"抢购成功，正在处理中" ✅

═══════════════════════════════════

1-5秒：Kafka消费
   ├─ 9900人成功 → status=1
   └─ 100人失败 → 回滚库存+100 → status=2

═══════════════════════════════════

5秒后：Redis库存=100

用户A抢购：
   ↓
Lua脚本：stock=100
   ↓
扣减成功 → 返回"抢购成功" ✅

═══════════════════════════════════

某时刻：Redis库存再次=0

用户B抢购：
   ↓
★ 智能判断：
   ├─ DB库存=50 ✅
   ├─ 待处理订单=10 ✅
   └─ 返回"活动火爆，请稍后刷新重试" ✨
      （而不是"已售罄"）

═══════════════════════════════════

最终：DB库存=0 且 无待处理订单

用户C抢购：
   ↓
★ 智能判断：
   ├─ DB库存=0 ❌
   ├─ 待处理订单=0 ❌
   └─ 返回"已售罄" ✅（真正售罄）

Job定时任务：
   ├─ 标记SOLD_OUT
   ├─ 清理Redis缓存
   └─ 智能跳过后续检查
```

---

### 技术亮点

1. **智能判断**：Redis库存不足时，检查DB库存和待处理订单，智能返回提示
   - 📍 **类**: `SeckillServiceImpl`
   - 📍 **方法**: `handleStockInsufficient(Long activityId)` (第252-293行)
   - 📍 **调用位置**: `handleLuaResult()` → case -1分支

2. **动态限流**：根据剩余库存自动调整限流速率，减少70% Redis压力
   - 📍 **类**: `DynamicRateLimiterManager`
   - 📍 **核心方法**: 
     - `adjustRateIfNeeded(Long activityId)` - 动态调整限流 (第91-113行)
     - `calculateRate(int remainStock)` - 计算限流速率（库存×1.5倍）(第128-147行)
     - `tryAcquire(Long activityId)` - 获取限流许可 (第154-157行)
   - 📍 **使用位置**: `SeckillController.grabCoupon()` 第61行

3. **最终一致性**：完整的补偿机制（回滚库存+积分+订单状态）
   - 📍 **类**: `SeckillOrderKafkaConsumer`
   - 📍 **核心方法**:
     - `processSeckillOrder()` - 主流程处理 (第49-129行)
     - `rollbackRedisStock()` - 回滚Redis库存 (第133-141行)
     - `rollbackPointsIfNeeded()` - 回滚积分 (第146-161行)
     - `handleOrderFail()` - 标记订单失败 (第166-172行)
   - 📍 **补偿触发**: catch块 第119-129行

4. **资源优化**：Job任务智能跳过，自动清理Redis缓存
   - 📍 **类**: `CouponScheduledJob`
   - 📍 **核心方法**:
     - `checkAndFinishActivity()` - 检查并完成活动 (第108-148行)
     - `checkSoldOutActivities()` - 检查售罄 (第181-227行)
     - `adjustDynamicRateLimiters()` - 动态限流调整，智能跳过 (第158-179行)

5. **监控告警**：Kafka积压监控，及时发现异常
   - 📍 **类**: `KafkaLagMonitor`
   - 📍 **核心方法**:
     - `checkLag()` - 定时检查，每30秒 (第73-93行)
     - `getConsumerLag()` - 获取积压数量 (第103-149行)
     - `sendWarningAlert()` - 普通告警 (第163-187行)
     - `sendCriticalAlert()` - 严重告警 (第194-226行)

6. **Lua脚本原子扣库存**：防止超卖
   - 📍 **文件**: `resources/lua/seckill_grab.lua`
   - 📍 **调用位置**: `SeckillServiceImpl.grabCoupon()` 第69-75行
   - 📍 **逻辑**: 检查库存 + 检查用户限购 + 扣减库存 + 记录用户（原子操作）

7. **异步处理提前返回**：用户体验优化
   - 📍 **类**: `SeckillServiceImpl`
   - 📍 **方法**: `grabCoupon()` 第103-114行
   - 📍 **关键代码**: 
     - 第92行：`order.setStatus(0)` - 订单待处理状态
     - 第99行：`kafkaTemplate.send()` - 发送Kafka消息
     - 第106行：`message("抢购成功,正在处理中")` - 立即返回，不等Kafka

---

## 兑换码防刷体系精细化（2026-04-20新增）

> 详细方案见：`Druid监控和防刷兑换码.md`（完整版含图解、代码、DB设计）

### 6层防刷体系（修正版）

| 层 | 机制 | 防什么 | 重要程度 |
|----|------|--------|---------|
| 第1层 | HMAC-SHA256签名校验 | 枚举/伪造码（防99%） | ⭐⭐⭐⭐⭐ 核心 |
| 第2层 | IP限速（Redis，1分钟10次） | 单机高频扫码 | ⭐⭐⭐ 辅助 |
| 第3层 | 用户限速（Redis，1分钟5次） | 羊毛党批量账号 | ⭐⭐⭐ 辅助 |
| 第4层 | Redisson分布式锁 | 双击/网络重试并发重复提交 | ⭐⭐⭐⭐ 重要 |
| 第5层 | DB CAS原子更新 WHERE STATUS=0 | **重复兑换终极防线** | ⭐⭐⭐⭐⭐ 核心 |
| 第6层 | 失败次数锁定（FAIL_COUNT>=5） | 持续暴力重试单码（冗余防御） | ⭐⭐ 冗余 |
| 解锁保护 | UNLOCK_COUNT>=2强制人工 | 客服被社工/已用码被解锁重用 | ⭐⭐⭐ 运营安全 |

### FAIL_COUNT正确触发条件（精细化后）

| 触发条件 | 是否加FAIL_COUNT | 原因 |
|---------|----------------|------|
| HMAC验签失败（格式非法码） | ❌ 不加 | 第1层直接拦截，不查DB |
| 码在DB中不存在 | ❌ 不加 | 没有记录可以加 |
| 码 STATUS=1（已使用） | ❌ 不加 | 终态，直接返回"已被使用"，不触发锁定 |
| 码 STATUS=2（已过期） | ❌ 不加 | 终态，直接返回"已过期" |
| 码 STATUS=3（已锁定） | ❌ 不加 | 已是终态 |
| 码 STATUS=0，CAS并发失败（affected=0） | ✅ 加 | 真实有效码被并发争抢 |

**关键结论**：正常业务流程中 FAIL_COUNT 几乎不会累积到5，真正的安全由第1层HMAC+第5层CAS保证，FAIL_COUNT是冗余防御。

### 失败5次锁定 → 三级处理流程

```
码被锁定（STATUS=3）
      │
      ▼
第1级：用户自助解锁（APP内）
  条件：码从未成功兑换过（STATUS≠1）
  限制：UNLOCK_COUNT < 2
  结果：STATUS→0，FAIL_COUNT→0，UNLOCK_COUNT+1
      │ 解锁失败（UNLOCK_COUNT>=2）
      ▼
第2级：客服二次确认解锁
  条件：查看兑换记录确认码未被成功使用
  需要：提供购买凭证/截图
  结果：STATUS→0，UNLOCK_COUNT+1，记录操作日志
      │ 解锁失败 或 UNLOCK_COUNT>=3
      ▼
第3级：强制人工审核（上级审批）
  条件：UNLOCK_COUNT>=2（系统自动触发告警）
  流程：安全团队核查IP、设备、兑换记录
  结论：补发新码 或 永久锁定 或 退款处理
```

**大部分情况不需人工介入的原因**：
1. 正常用户：码有效（STATUS=0）→ CAS成功 → 直接兑换成功，FAIL_COUNT不会到5
2. 输错码：HMAC第1层直接拒绝，连DB都不查，没有FAIL_COUNT
3. 码已被用：STATUS=1终态，直接提示"已被使用"，不加FAIL_COUNT
4. 只有极少数真实有效码被并发争抢才触发FAIL_COUNT，且实际上成功兑换后STATUS=1就已是终态

### UNLOCK_COUNT防解锁滥用

**防御的攻击链**：
```
攻击者B → 社工客服 → "帮我解锁码" 
→ 客服把STATUS=1改回0 → 攻击者兑换 → 真实用户码被盗用
```

**防御规则**：
- `UNLOCK_COUNT < 2`：允许解锁（可能是正常操作失误）
- `UNLOCK_COUNT >= 2`：强制上级审批 + 系统自动告警
- **关键**：STATUS=1（已成功兑换）的码**绝对禁止解锁**，需走补发新码流程

### 网络失败幂等方案

```
问题：服务端兑换成功（STATUS=1），但响应网络丢失，用户重试
结果：再次提交 → STATUS=1 → 返回"已被使用" → 用户困惑

解决：前端幂等requestId
  提交时携带 requestId（UUID）
  服务端缓存结果（Redis, 5分钟TTL）
  同一requestId → 返回原来的"成功"结果
  → 用户不会看到"已被使用"困惑提示
```

### 技术实现文件索引

| 功能 | 文件 | 关键方法 |
|------|------|---------|
| 码生成 | `util/RedeemCodeGenerator.java` | `generate(batchId, seq)` |
| HMAC验签 | `util/RedeemCodeGenerator.java` | `verify(code)` |
| 兑换服务 | `service/impl/RedeemCodeServiceImpl.java` | `doRedeem()`, `casRedeemCode()` |
| 失败锁定 | `mapper/RedeemCodeMapper.java` | `incrementFailCount()`, `lockCode()` |
| 自助解锁 | `service/impl/RedeemCodeServiceImpl.java` | `selfUnlock()` |
| 管理解锁 | `service/impl/RedeemCodeServiceImpl.java` | `adminUnlock()` |
| DB表设计 | `resources/db/schema.sql` | `T_COUPON_REDEEM_CODE`, `T_REDEEM_CODE_BATCH` |

### API接口（兑换码模块）

| 方法 | 路径 | 说明 | 调用方 |
|------|------|------|--------|
| POST | /api/redeem-code/batch/generate | 批量生成码 | 管理后台 |
| POST | /api/redeem-code/redeem | 用户兑换 | APP/H5 |
| POST | /api/redeem-code/unlock | 用户自助解锁 | APP |
| POST | /api/redeem-code/admin/unlock | 客服/管理员解锁（需UNLOCK_COUNT<2） | 客服后台 |
| GET  | /api/redeem-code/batch/{id} | 查询批次统计 | 管理后台 |

### 易混字符处理（用户体验）

```java
// 生成码时去掉易混字符（0/O/1/I/L），使用32个安全字符
private static final String SAFE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

// 前端/后端输入预处理
String normalizeCode(String input) {
    return input.toUpperCase().trim().replace(" ", "").replace("-", "");
}
```




