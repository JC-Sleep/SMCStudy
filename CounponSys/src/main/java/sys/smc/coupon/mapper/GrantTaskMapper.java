package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import sys.smc.coupon.entity.GrantTask;

import java.util.Date;
import java.util.List;

/**
 * 兑换码发券补偿任务Mapper
 */
@Mapper
public interface GrantTaskMapper extends BaseMapper<GrantTask> {

    /**
     * 查询需要重试的任务（status=0 且 nextRetryTime已到）
     * 每次最多取50条，防止Job一次处理太多
     */
    @Select("SELECT * FROM T_GRANT_TASK " +
            "WHERE STATUS = 0 " +
            "AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}) " +
            "AND RETRY_COUNT < MAX_RETRY " +
            "AND ROWNUM <= 50 " +
            "ORDER BY CREATE_TIME ASC")
    List<GrantTask> selectPendingTasks(@Param("now") Date now);

    /**
     * 标记任务成功
     */
    @Update("UPDATE T_GRANT_TASK " +
            "SET STATUS=1, COUPON_ID=#{couponId}, UPDATE_TIME=SYSTIMESTAMP " +
            "WHERE ID=#{id}")
    int markSuccess(@Param("id") Long id, @Param("couponId") Long couponId);

    /**
     * 标记任务失败（增加重试次数，设置下次重试时间）
     * 若 retryCount+1 >= maxRetry → status改为2（失败超限，人工介入）
     */
    @Update("UPDATE T_GRANT_TASK " +
            "SET RETRY_COUNT = RETRY_COUNT + 1, " +
            "    FAIL_REASON = #{failReason}, " +
            "    NEXT_RETRY_TIME = #{nextRetryTime}, " +
            "    STATUS = CASE WHEN RETRY_COUNT + 1 >= MAX_RETRY THEN 2 ELSE 0 END, " +
            "    UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID=#{id}")
    int markFailed(@Param("id") Long id,
                   @Param("failReason") String failReason,
                   @Param("nextRetryTime") Date nextRetryTime);

    /**
     * 检查某个code是否已有成功的发券任务（幂等判断）
     * 防止Job重复发券
     */
    @Select("SELECT COUNT(*) FROM T_GRANT_TASK WHERE REDEEM_CODE=#{code} AND STATUS=1")
    int countSuccessByCode(@Param("code") String code);
}

