package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import sys.smc.coupon.entity.CouponRedeemCode;

import java.util.Date;
import java.util.List;

@Mapper
public interface CouponRedeemCodeMapper extends BaseMapper<CouponRedeemCode> {

    /**
     * CAS原子更新状态：0(未使用) → 1(已使用)
     * 返回受影响行数：1=成功，0=已被抢先兑换（并发安全）
     */
    @Update("UPDATE T_COUPON_REDEEM_CODE " +
            "SET STATUS=1, USER_ID=#{userId}, REDEEM_TIME=#{redeemTime}, " +
            "    REDEEM_IP=#{redeemIp}, REDEEM_CHANNEL=#{channel}, UPDATE_TIME=#{redeemTime} " +
            "WHERE CODE=#{code} AND STATUS=0 AND DELETED=0")
    int casRedeemCode(@Param("code") String code,
                      @Param("userId") String userId,
                      @Param("redeemTime") Date redeemTime,
                      @Param("redeemIp") String redeemIp,
                      @Param("channel") String channel);

    /**
     * 增加失败次数，超过MAX_FAIL_COUNT自动锁定
     */
    @Update("UPDATE T_COUPON_REDEEM_CODE " +
            "SET FAIL_COUNT = FAIL_COUNT + 1, " +
            "    STATUS = CASE WHEN FAIL_COUNT + 1 >= " + CouponRedeemCode.MAX_FAIL_COUNT + " THEN 3 ELSE STATUS END, " +
            "    UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE CODE=#{code} AND DELETED=0")
    int incrementFailCount(@Param("code") String code);

    /**
     * 批量插入兑换码（MyBatis-Plus不原生支持Oracle批量，手动写）
     */
    void batchInsert(@Param("list") List<CouponRedeemCode> list);
}

