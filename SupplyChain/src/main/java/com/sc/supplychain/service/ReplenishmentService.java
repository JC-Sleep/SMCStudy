package com.sc.supplychain.service;

import com.sc.supplychain.dto.request.ReplenishmentRuleRequest;
import com.sc.supplychain.entity.ReplenishmentOrder;

import java.util.List;

public interface ReplenishmentService {
    Long createRule(ReplenishmentRuleRequest req);
    /** 扫描所有启用规则，Redis available < minQty 则发 Kafka 触发补货 */
    void checkAndTrigger();
    List<ReplenishmentOrder> listOrders(String status);
    void confirmOrder(Long orderId);
}
