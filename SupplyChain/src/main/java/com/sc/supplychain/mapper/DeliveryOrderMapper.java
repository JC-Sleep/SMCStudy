package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.DeliveryOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DeliveryOrderMapper extends BaseMapper<DeliveryOrder> {

    /** 状态机原子 update（B3 经验复用） */
    @Update("UPDATE sc_delivery_order SET status = #{newStatus} " +
            "WHERE id = #{id} AND status = #{expectedStatus}")
    int transition(@Param("id") Long id,
                   @Param("expectedStatus") String expectedStatus,
                   @Param("newStatus") String newStatus);

    /** 抢单/派单绑定：仅 PENDING/GRABBING 且 rider_id IS NULL 才绑定 */
    @Update("UPDATE sc_delivery_order SET rider_id=#{riderId}, status='ASSIGNED', assign_time=NOW() " +
            "WHERE id=#{deliveryId} AND status IN ('PENDING','GRABBING') AND rider_id IS NULL")
    int bindRiderIfPending(@Param("deliveryId") Long deliveryId,
                           @Param("riderId") Long riderId);

    @Update("UPDATE sc_delivery_order SET reassign_count = reassign_count + 1, " +
            "status='PENDING', rider_id=NULL WHERE id=#{id}")
    int incrReassignCount(@Param("id") Long id);

    @Select("SELECT * FROM sc_delivery_order WHERE status='ASSIGNED' AND expected_pickup_time < NOW()")
    List<DeliveryOrder> selectPickupTimeout();

    @Select("SELECT * FROM sc_delivery_order WHERE status IN ('PICKING','IN_TRANSIT') " +
            "AND expected_deliver_time < NOW()")
    List<DeliveryOrder> selectDeliverTimeout();

    @Select("SELECT * FROM sc_delivery_order WHERE status='PENDING' " +
            "AND warehouse_id=#{warehouseId} ORDER BY create_time ASC LIMIT 100")
    List<DeliveryOrder> selectPendingByWarehouse(@Param("warehouseId") Long warehouseId);
}
