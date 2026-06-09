package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.DeliveryGrabPool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DeliveryGrabPoolMapper extends BaseMapper<DeliveryGrabPool> {

    /** 抢单：仅当 grabbed_by IS NULL 时才能抢成功（DB 层兜底）*/
    @Update("UPDATE sc_delivery_grab_pool SET grabbed_by=#{riderId}, grab_time=NOW() " +
            "WHERE delivery_id=#{deliveryId} AND grabbed_by IS NULL AND expire_time > NOW()")
    int grab(@Param("deliveryId") Long deliveryId, @Param("riderId") Long riderId);

    /** 查询某仓库可抢的池（未过期、未被抢）*/
    @Select("SELECT * FROM sc_delivery_grab_pool WHERE warehouse_id=#{warehouseId} " +
            "AND grabbed_by IS NULL AND expire_time > NOW() ORDER BY create_time ASC")
    List<DeliveryGrabPool> selectAvailable(@Param("warehouseId") Long warehouseId);
}

