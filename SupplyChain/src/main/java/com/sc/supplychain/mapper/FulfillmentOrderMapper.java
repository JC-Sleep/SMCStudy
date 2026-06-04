package com.sc.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sc.supplychain.entity.FulfillmentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FulfillmentOrderMapper extends BaseMapper<FulfillmentOrder> {

    /**
     * B3+B4 修复：原子条件 update。
     * 仅当订单当前状态为 PENDING 或 PAID 时才允许取消。
     * affected=1 表示本线程抢到取消权；affected=0 表示并发已被取消或状态不允许。
     */
    @Update("UPDATE sc_fulfillment_order SET status='CANCELLED' " +
            "WHERE order_no=#{orderNo} AND status IN ('PENDING','PAID')")
    int cancelIfCancelable(@Param("orderNo") String orderNo);
}
