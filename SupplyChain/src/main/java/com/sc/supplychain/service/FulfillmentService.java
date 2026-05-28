package com.sc.supplychain.service;

import com.sc.supplychain.dto.request.OrderCreateRequest;
import com.sc.supplychain.entity.FulfillmentOrder;

import java.util.List;

/** O2O 履约服务 */
public interface FulfillmentService {
    /** 下单：Redis预扣 → 写订单 → 发MQ落库 */
    String createOrder(OrderCreateRequest req);
    /** 取消/超时：释放预扣 */
    void cancelOrder(String orderNo);
    /** 出库确认：FIFO分配批次 → confirmDeduct */
    void outbound(String orderNo);
    /** 支付回调 */
    void onPaid(String orderNo);
    List<FulfillmentOrder> listByStore(Long storeId);
    FulfillmentOrder getByOrderNo(String orderNo);
}
