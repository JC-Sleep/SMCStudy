# Plan: 电讯行业优惠券系统（CouponSys）完整实现方案

> **实现状态**: ✅ 全部核心模块已完成（2026-03-25）
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
>
> **性能指标**: 支持千万级用户，5-10W QPS并发

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

