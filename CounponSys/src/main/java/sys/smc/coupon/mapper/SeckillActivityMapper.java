package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import sys.smc.coupon.entity.SeckillActivity;

import java.util.List;

/**
 * 秒杀活动Mapper
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 扣减库存(数据库层面)
     */
    @Update("UPDATE T_SECKILL_ACTIVITY SET REMAIN_STOCK = REMAIN_STOCK - #{count}, " +
            "UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{activityId} AND REMAIN_STOCK >= #{count}")
    int deductStock(@Param("activityId") Long activityId, @Param("count") int count);

    /**
     * 回滚库存
     */
    @Update("UPDATE T_SECKILL_ACTIVITY SET REMAIN_STOCK = REMAIN_STOCK + #{count}, " +
            "UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{activityId}")
    int rollbackStock(@Param("activityId") Long activityId, @Param("count") int count);

    /**
     * 更新活动状态为售罄
     */
    @Update("UPDATE T_SECKILL_ACTIVITY SET STATUS = 4, UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{activityId} AND REMAIN_STOCK = 0")
    int updateToSoldOut(@Param("activityId") Long activityId);

    /**
     * 查询即将开始的活动(用于预热)
     */
    @Select("SELECT * FROM T_SECKILL_ACTIVITY WHERE STATUS = 0 AND DELETED = 0 " +
            "AND START_TIME BETWEEN SYSTIMESTAMP AND SYSTIMESTAMP + INTERVAL '#{minutes}' MINUTE")
    List<SeckillActivity> findUpcomingActivities(@Param("minutes") int minutes);

    /**
     * 查询进行中的活动
     */
    @Select("SELECT * FROM T_SECKILL_ACTIVITY WHERE STATUS = 1 AND DELETED = 0 " +
            "AND START_TIME <= SYSTIMESTAMP AND END_TIME > SYSTIMESTAMP")
    List<SeckillActivity> findOngoingActivities();
}

