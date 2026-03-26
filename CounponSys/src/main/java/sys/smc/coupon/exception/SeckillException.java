package sys.smc.coupon.exception;

import lombok.Getter;

/**
 * 秒杀业务异常
 */
@Getter
public class SeckillException extends RuntimeException {

    private final Integer code;

    public SeckillException(String message) {
        super(message);
        this.code = 500;
    }

    public SeckillException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    // ============ 常见异常静态方法 ============

    public static SeckillException activityNotFound() {
        return new SeckillException(404, "秒杀活动不存在");
    }

    public static SeckillException activityNotStarted() {
        return new SeckillException(400, "秒杀活动未开始");
    }

    public static SeckillException activityEnded() {
        return new SeckillException(400, "秒杀活动已结束");
    }

    public static SeckillException soldOut() {
        return new SeckillException(400, "已售罄");
    }

    public static SeckillException grabLimitExceeded() {
        return new SeckillException(400, "超过限购数量");
    }

    public static SeckillException alreadyGrabbed() {
        return new SeckillException(400, "您已抢购过该活动");
    }

    public static SeckillException pointsInsufficient() {
        return new SeckillException(400, "积分不足");
    }

    public static SeckillException balanceInsufficient() {
        return new SeckillException(400, "余额不足");
    }

    public static SeckillException vipRequired() {
        return new SeckillException(400, "该活动仅限VIP用户参与");
    }

    public static SeckillException vipLevelNotMet() {
        return new SeckillException(400, "VIP等级不满足活动要求");
    }

    public static SeckillException systemBusy() {
        return new SeckillException(503, "系统繁忙，请稍后重试");
    }

    public static SeckillException rateLimitExceeded() {
        return new SeckillException(429, "请求过于频繁，请稍后重试");
    }
}

