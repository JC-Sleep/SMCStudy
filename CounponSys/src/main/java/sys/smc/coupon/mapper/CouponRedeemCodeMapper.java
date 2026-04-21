package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
     * 注意：只在 STATUS=0（CAS并发失败）时调用，终态码不应调用此方法
     */
    @Update("UPDATE T_COUPON_REDEEM_CODE " +
            "SET FAIL_COUNT = FAIL_COUNT + 1, " +
            "    STATUS = CASE WHEN FAIL_COUNT + 1 >= " + CouponRedeemCode.MAX_FAIL_COUNT + " THEN 3 ELSE STATUS END, " +
            "    UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE CODE=#{code} AND DELETED=0")
    int incrementFailCount(@Param("code") String code);

    /**
     * 批量标记过期（修复瑕疵：过期Job缺失）
     * 每次最多处理 limit 条，防止大事务锁表
     * 只处理 STATUS=0（未使用）的码，STATUS=1/3 不需要改
     */
    @Update("UPDATE T_COUPON_REDEEM_CODE " +
            "SET STATUS=2, UPDATE_TIME=SYSTIMESTAMP " +
            "WHERE STATUS=0 AND DELETED=0 AND EXPIRE_TIME < SYSTIMESTAMP " +
            "AND ROWNUM <= #{limit}")
    int batchExpire(@Param("limit") int limit);

    /**
     * 管理员解锁码（修复瑕疵：缺少解锁功能）
     * 只允许解锁 STATUS=3（被锁定的码），STATUS=1（已使用）绝对不允许解锁
     * 解锁后：STATUS→0，FAIL_COUNT→0，UNLOCK_COUNT+1
     */
    @Update("UPDATE T_COUPON_REDEEM_CODE " +
            "SET STATUS=0, FAIL_COUNT=0, UNLOCK_COUNT=UNLOCK_COUNT+1, UPDATE_TIME=SYSTIMESTAMP " +
            "WHERE CODE=#{code} AND STATUS=3 AND DELETED=0")
    int adminUnlock(@Param("code") String code);

    /**
     * 查询某用户在某批次已兑换成功的数量（修复瑕疵：同批次重复兑换）
     */
    @Select("SELECT COUNT(*) FROM T_COUPON_REDEEM_CODE " +
            "WHERE USER_ID=#{userId} AND BATCH_ID=#{batchId} AND STATUS=1 AND DELETED=0")
    int countRedeemedByUserAndBatch(@Param("userId") String userId,
                                    @Param("batchId") Long batchId);

    /**
     * 批量插入兑换码（MyBatis-Plus不原生支持Oracle批量，手动写）
     */
    void batchInsert(@Param("list") List<CouponRedeemCode> list);
}

