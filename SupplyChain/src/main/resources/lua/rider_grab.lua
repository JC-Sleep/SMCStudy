-- 骑手抢单 Lua（防超抢核心，复用 P0 B7 SETNX 思路）
-- KEYS[1] = sc:delivery:grab:lock:{deliveryId}
-- ARGV[1] = riderId
-- ARGV[2] = expireSec
-- 返回：1=抢到 0=已被抢
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 1

