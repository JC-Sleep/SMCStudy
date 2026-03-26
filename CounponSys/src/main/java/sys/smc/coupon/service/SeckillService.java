package sys.smc.coupon.service;

import sys.smc.coupon.dto.request.SeckillGrabRequest;
import sys.smc.coupon.dto.response.SeckillActivityDTO;
import sys.smc.coupon.dto.response.SeckillGrabResult;
import sys.smc.coupon.entity.SeckillActivity;

import java.util.List;

/**
 * 秒杀服务接口
 */
public interface SeckillService {

    /**
     * 抢购优惠券
     * @param request 抢购请求
     * @return 抢购结果
     */
    SeckillGrabResult grabCoupon(SeckillGrabRequest request);

    /**
     * 获取秒杀活动列表
     * @param status 状态(可选)
     * @return 活动列表
     */
    List<SeckillActivityDTO> listActivities(Integer status);

    /**
     * 获取活动详情
     * @param activityId 活动ID
     * @return 活动详情
     */
    SeckillActivityDTO getActivityDetail(Long activityId);

    /**
     * 获取活动剩余库存
     * @param activityId 活动ID
     * @return 剩余库存
     */
    Integer getRemainStock(Long activityId);

    /**
     * 预热活动库存到Redis
     * @param activity 活动
     */
    void warmUpStock(SeckillActivity activity);

    /**
     * 检查用户是否已抢购
     * @param activityId 活动ID
     * @param userId 用户ID
     * @return 已抢数量
     */
    Integer getUserGrabbedCount(Long activityId, String userId);
}

