package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.InventoryBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InventoryBatchMapper extends BaseMapper<InventoryBatch> {

    /**
     * FIFO：按入库时间升序查询有剩余库存的批次
     */
    @Select("SELECT * FROM sc_inventory_batch " +
            "WHERE sku_id=#{skuId} AND warehouse_id=#{warehouseId} AND remain_qty > 0 " +
            "AND status NOT IN ('EXPIRED','WRITTEN_OFF') " +
            "ORDER BY inbound_time ASC")
    List<InventoryBatch> selectFifoBatches(@Param("skuId") Long skuId,
                                           @Param("warehouseId") Long warehouseId);

    /**
     * 扣减批次剩余量
     */
    @Update("UPDATE sc_inventory_batch SET remain_qty = remain_qty - #{qty} " +
            "WHERE id = #{id} AND remain_qty >= #{qty}")
    int deductBatchRemain(@Param("id") Long id, @Param("qty") int qty);
}

