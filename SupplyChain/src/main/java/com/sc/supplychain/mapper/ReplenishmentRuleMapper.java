package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.ReplenishmentRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReplenishmentRuleMapper extends BaseMapper<ReplenishmentRule> {

    @Select("SELECT * FROM sc_replenishment_rule WHERE is_enabled = 1")
    List<ReplenishmentRule> selectAllEnabled();
}
