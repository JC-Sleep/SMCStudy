package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * 增加锁定库存、减少可用库存（MQ 异步落库用）
     */
    @Update("UPDATE sc_inventory SET locked_qty = locked_qty + #{qty}, available_qty = available_qty - #{qty} " +
            "WHERE sku_id = #{skuId} AND warehouse_id = #{warehouseId} AND available_qty >= #{qty}")
    int lockQty(@Param("skuId") Long skuId,
                @Param("warehouseId") Long warehouseId,
                @Param("qty") int qty);

    /**
     * 释放锁定库存（订单取消/超时）
     */
    @Update("UPDATE sc_inventory SET locked_qty = locked_qty - #{qty}, available_qty = available_qty + #{qty} " +
            "WHERE sku_id = #{skuId} AND warehouse_id = #{warehouseId} AND locked_qty >= #{qty}")
    int unlockQty(@Param("skuId") Long skuId,
                  @Param("warehouseId") Long warehouseId,
                  @Param("qty") int qty);

    /**
     * 出库确认（扣减锁定库存和总库存）
     */
    @Update("UPDATE sc_inventory SET locked_qty = locked_qty - #{qty}, total_qty = total_qty - #{qty} " +
            "WHERE sku_id = #{skuId} AND warehouse_id = #{warehouseId} AND locked_qty >= #{qty}")
    int confirmDeductQty(@Param("skuId") Long skuId,
                         @Param("warehouseId") Long warehouseId,
                         @Param("qty") int qty);

    /**
     * 入库：增加可用库存和总库存
     */
    @Update("UPDATE sc_inventory SET available_qty = available_qty + #{qty}, total_qty = total_qty + #{qty} " +
            "WHERE sku_id = #{skuId} AND warehouse_id = #{warehouseId}")
    int addQty(@Param("skuId") Long skuId,
               @Param("warehouseId") Long warehouseId,
               @Param("qty") int qty);
}
