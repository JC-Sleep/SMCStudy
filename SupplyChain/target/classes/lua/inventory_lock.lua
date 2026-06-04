-- 库存预扣 Lua Script（防超卖核心）
-- 原子性：检查库存 + 扣减在同一 Redis 命令中执行，不会并发超卖
--
-- KEYS[1] = inventory:available:{warehouseId}:{skuId}
-- ARGV[1] = 扣减数量（正整数）
--
-- 返回值:
--   >= 0  : 扣减成功，返回扣减后剩余可用量
--   -1    : 库存不足（当前库存 < 扣减量）
--   -2    : key 不存在（库存未初始化，需先预热）

local stock = redis.call('GET', KEYS[1])

if stock == false then
    -- key 不存在，库存未预热
    return -2
end

local current = tonumber(stock)
local deduct  = tonumber(ARGV[1])

if current < deduct then
    -- 库存不足
    return -1
end

-- 原子扣减
return redis.call('DECRBY', KEYS[1], deduct)
