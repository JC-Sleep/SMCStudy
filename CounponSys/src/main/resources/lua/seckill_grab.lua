-- =============================================
-- 秒杀抢购Lua脚本
-- 原子操作：检查库存 + 检查用户限购 + 扣减库存 + 记录用户
-- =============================================
-- KEYS[1]: 库存Key (seckill:stock:{activityId})
-- KEYS[2]: 用户已抢次数Key (seckill:user:{activityId}:{userId})
-- ARGV[1]: 用户限购数量
-- ARGV[2]: 抢购数量(通常为1)

-- 返回值:
-- 1: 成功
-- -1: 库存不足
-- -2: 超过限购数量
-- -3: 活动不存在

-- 检查库存Key是否存在
local stockExists = redis.call('EXISTS', KEYS[1])
if stockExists == 0 then
    return -3
end

-- 获取当前库存
local stock = tonumber(redis.call('GET', KEYS[1]))
local grabCount = tonumber(ARGV[2])

-- 检查库存是否充足
if stock < grabCount then
    return -1
end

-- 获取用户已抢次数
local userGrabbed = tonumber(redis.call('GET', KEYS[2]) or '0')
local limitPerUser = tonumber(ARGV[1])

-- 检查是否超过限购
if userGrabbed + grabCount > limitPerUser then
    return -2
end

-- 扣减库存
redis.call('DECRBY', KEYS[1], grabCount)

-- 增加用户已抢次数
redis.call('INCRBY', KEYS[2], grabCount)

-- 设置用户Key过期时间(活动结束后自动清理,设为24小时)
redis.call('EXPIRE', KEYS[2], 86400)

return 1

