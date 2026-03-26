package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import sys.smc.coupon.entity.Coupon;

import java.util.List;

/**
 * 优惠券Mapper
 */
@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 查询用户已领取某模板的数量
     */
    @Select("SELECT COUNT(*) FROM T_COUPON WHERE USER_ID = #{userId} AND TEMPLATE_ID = #{templateId} AND DELETED = 0")
    int countUserClaimed(@Param("userId") String userId, @Param("templateId") Long templateId);

    /**
     * 更新过期的优惠券状态
     */
    @Update("UPDATE T_COUPON SET STATUS = 3, UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE STATUS = 1 AND VALID_END_TIME < SYSTIMESTAMP AND DELETED = 0")
    int updateExpiredCoupons();

    /**
     * 查询即将过期的券(用于发送提醒)
     */
    @Select("SELECT * FROM T_COUPON WHERE STATUS = 1 AND DELETED = 0 " +
            "AND VALID_END_TIME BETWEEN SYSTIMESTAMP AND SYSTIMESTAMP + #{days}")
    List<Coupon> findExpiringCoupons(@Param("days") int days);

    /**
     * 核销优惠券
     */
    @Update("UPDATE T_COUPON SET STATUS = 2, USE_TIME = SYSTIMESTAMP, ORDER_NO = #{orderNo}, " +
            "UPDATE_TIME = SYSTIMESTAMP WHERE ID = #{couponId} AND STATUS = 1 AND DELETED = 0")
    int redeemCoupon(@Param("couponId") Long couponId, @Param("orderNo") String orderNo);
}

