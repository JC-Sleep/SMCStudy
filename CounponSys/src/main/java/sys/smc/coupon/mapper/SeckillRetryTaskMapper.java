package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import sys.smc.coupon.entity.SeckillRetryTask;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀订单重试任务 Mapper（2026-04-22 新增）
 */
@Mapper
public interface SeckillRetryTaskMapper extends BaseMapper<SeckillRetryTask> {

    /**
     * 查询到期的待重试任务
     * 每次最多取50条，防止单次Job处理太多影响DB性能
     * 按创建时间正序，优先处理等待最久的任务
     */
    @Select("SELECT * FROM T_SECKILL_RETRY_TASK " +
            "WHERE STATUS = 0 " +
            "AND RETRY_COUNT < MAX_RETRY " +
            "AND (NEXT_RETRY_TIME IS NULL OR NEXT_RETRY_TIME <= #{now}) " +
            "AND ROWNUM <= 50 " +
            "ORDER BY CREATE_TIME ASC")
    List<SeckillRetryTask> selectPendingTasks(@Param("now") LocalDateTime now);

    /**
     * 标记重试成功（status=1）
     */
    @Update("UPDATE T_SECKILL_RETRY_TASK " +
            "SET STATUS = 1, UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{id}")
    int markSuccess(@Param("id") Long id);

    /**
     * 标记本次重试失败（更新重试次数和下次重试时间）
     * 当 RETRY_COUNT+1 >= MAX_RETRY 时，自动将 STATUS 改为 2（超限需人工）
     */
    @Update("UPDATE T_SECKILL_RETRY_TASK " +
            "SET RETRY_COUNT = RETRY_COUNT + 1, " +
            "    FAIL_REASON = #{failReason}, " +
            "    NEXT_RETRY_TIME = #{nextRetryTime}, " +
            "    STATUS = CASE WHEN RETRY_COUNT + 1 >= MAX_RETRY THEN 2 ELSE 0 END, " +
            "    UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{id}")
    int markFailed(@Param("id") Long id,
                   @Param("failReason") String failReason,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime);
}

