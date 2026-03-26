package sys.smc.coupon.service;

/**
 * 积分服务接口
 * 对接内部积分系统或第三方积分平台
 */
public interface PointsService {

    /**
     * 扣减积分
     * @param userId 用户ID
     * @param channel 积分渠道
     * @param points 积分数量
     * @param reason 扣减原因
     * @return 是否成功
     */
    boolean deductPoints(String userId, Integer channel, Integer points, String reason);

    /**
     * 回滚积分
     * @param userId 用户ID
     * @param channel 积分渠道
     * @param points 积分数量
     * @param reason 回滚原因
     * @return 是否成功
     */
    boolean rollbackPoints(String userId, Integer channel, Integer points, String reason);

    /**
     * 查询用户积分余额
     * @param userId 用户ID
     * @param channel 积分渠道
     * @return 积分余额
     */
    Integer getBalance(String userId, Integer channel);
}

