package sys.smc.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import sys.smc.coupon.dto.request.ClaimCouponRequest;
import sys.smc.coupon.dto.request.RedeemCouponRequest;
import sys.smc.coupon.dto.response.CouponDTO;
import sys.smc.coupon.dto.response.RedeemResult;
import sys.smc.coupon.entity.Coupon;

import java.util.List;

/**
 * 优惠券服务接口
 */
public interface CouponService extends IService<Coupon> {

    /**
     * 领取优惠券
     * @param request 领取请求
     * @return 券信息
     */
    CouponDTO claimCoupon(ClaimCouponRequest request);

    /**
     * 核销优惠券
     * @param request 核销请求
     * @return 核销结果
     */
    RedeemResult redeemCoupon(RedeemCouponRequest request);

    /**
     * 查询用户优惠券列表
     * @param userId 用户ID
     * @param status 状态(可选)
     * @return 券列表
     */
    List<CouponDTO> listUserCoupons(String userId, Integer status);

    /**
     * 根据券码查询
     * @param couponCode 券码
     * @return 券信息
     */
    CouponDTO getCouponByCode(String couponCode);

    /**
     * 发放优惠券(内部调用)
     * @param userId 用户ID
     * @param userMobile 手机号
     * @param templateId 模板ID
     * @param grantType 发放类型
     * @param grantSource 发放来源
     * @return 券实体
     */
    Coupon grantCoupon(String userId, String userMobile, Long templateId, Integer grantType, String grantSource);

    /**
     * 处理过期优惠券
     * @return 处理数量
     */
    int processExpiredCoupons();
}

