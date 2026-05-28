package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.ExpiryWarning;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExpiryWarningMapper extends BaseMapper<ExpiryWarning> {

    @Select("SELECT * FROM sc_expiry_warning WHERE is_handled = 0 ORDER BY warn_time DESC")
    List<ExpiryWarning> selectUnhandled();

    @Select("SELECT b.* FROM sc_inventory_batch b " +
            "WHERE b.expire_date IS NOT NULL " +
            "AND b.remain_qty > 0 " +
            "AND b.status NOT IN ('EXPIRED','WRITTEN_OFF') " +
            "AND DATEDIFF(b.expire_date, CURDATE()) <= #{warnDaysNear} " +
            "ORDER BY b.expire_date ASC")
    List<com.sc.supplychain.entity.InventoryBatch> selectNearExpiryBatches(@Param("warnDaysNear") int warnDaysNear);
}
