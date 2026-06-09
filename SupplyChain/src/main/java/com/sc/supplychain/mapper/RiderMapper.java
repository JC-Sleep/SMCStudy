package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.Rider;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RiderMapper extends BaseMapper<Rider> {

    /** 查询某仓库 IDLE 且 在线 且 有空位的骑手（用于派单候选） */
    @Select("SELECT * FROM sc_rider " +
            "WHERE warehouse_id = #{warehouseId} " +
            "AND online = 1 AND status = 'IDLE' AND current_load < max_parallel " +
            "ORDER BY rating DESC")
    List<Rider> selectAvailable(@Param("warehouseId") Long warehouseId);

    /** 原子接单：load+1 + status=BUSY，仅当 IDLE/BUSY 且未满载时成功 */
    @Update("UPDATE sc_rider SET current_load = current_load + 1, " +
            "status = 'BUSY' " +
            "WHERE id = #{riderId} AND online = 1 AND current_load < max_parallel")
    int incrLoadIfAvailable(@Param("riderId") Long riderId);

    /** 完成 1 单：load-1，若归零则 status=IDLE */
    @Update("UPDATE sc_rider SET current_load = GREATEST(current_load - 1, 0), " +
            "status = CASE WHEN current_load - 1 <= 0 THEN 'IDLE' ELSE 'BUSY' END, " +
            "today_orders = today_orders + 1 " +
            "WHERE id = #{riderId}")
    int decrLoadOnFinish(@Param("riderId") Long riderId);

    /** 心跳：更新位置和心跳时间 */
    @Update("UPDATE sc_rider SET current_lng=#{lng}, current_lat=#{lat}, " +
            "last_heartbeat=NOW(), online=1 WHERE id=#{riderId}")
    int heartbeat(@Param("riderId") Long riderId,
                  @Param("lng") java.math.BigDecimal lng,
                  @Param("lat") java.math.BigDecimal lat);

    /** 心跳超时下线（OfflineCheckJob 调用） */
    @Update("UPDATE sc_rider SET online=0, status='OFFLINE' " +
            "WHERE online=1 AND last_heartbeat < #{threshold}")
    int offlineExpiredHeartbeat(@Param("threshold") java.time.LocalDateTime threshold);
}

