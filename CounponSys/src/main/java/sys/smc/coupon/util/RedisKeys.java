package sys.smc.coupon.util;

/**
 * Redis Key常量
 */
public class RedisKeys {

    private RedisKeys() {}

    // ============ 优惠券相关 ============
    /** 券模板缓存 */
    public static final String TEMPLATE_CACHE = "coupon:template:";
    
    /** 用户每日领取次数 */
    public static final String USER_CLAIM_COUNT = "coupon:claim:daily:";
    
    /** 领取分布式锁 */
    public static final String CLAIM_LOCK = "coupon:lock:claim:";

    // ============ 秒杀相关 ============
    /** 秒杀活动库存 */
    public static final String SECKILL_STOCK = "seckill:stock:";
    
    /** 秒杀用户已抢次数 */
    public static final String SECKILL_USER_GRABBED = "seckill:user:";
    
    /** 秒杀活动信息缓存 */
    public static final String SECKILL_ACTIVITY = "seckill:activity:";
    
    /** 秒杀抢购锁 */
    public static final String SECKILL_LOCK = "seckill:lock:";

    /**
     * 获取秒杀库存Key
     */
    public static String getSeckillStockKey(Long activityId) {
        return SECKILL_STOCK + activityId;
    }

    /**
     * 获取用户秒杀已抢次数Key
     */
    public static String getSeckillUserKey(Long activityId, String userId) {
        return SECKILL_USER_GRABBED + activityId + ":" + userId;
    }

    /**
     * 获取秒杀活动缓存Key
     */
    public static String getSeckillActivityKey(Long activityId) {
        return SECKILL_ACTIVITY + activityId;
    }

    /**
     * 获取领取锁Key
     */
    public static String getClaimLockKey(String userId, Long templateId) {
        return CLAIM_LOCK + userId + ":" + templateId;
    }
}

