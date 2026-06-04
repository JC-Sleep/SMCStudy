package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.InventoryLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InventoryLogMapper extends BaseMapper<InventoryLog> {

    /**
     * 幂等检查：判断 (refNo, opType) 是否已落库过。
     * Kafka 消费者在 update DB 前先调用，已存在则直接 ack 跳过，防止重复扣减。
     */
    @Select("SELECT COUNT(*) FROM sc_inventory_log WHERE ref_no=#{refNo} AND op_type=#{opType}")
    int countByRefNoAndOpType(@Param("refNo") String refNo, @Param("opType") String opType);
}
