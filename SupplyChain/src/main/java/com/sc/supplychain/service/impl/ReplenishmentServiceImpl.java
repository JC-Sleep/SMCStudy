package com.sc.supplychain.service.impl;

import com.sc.supplychain.dto.ReplenishmentMessage;
import com.sc.supplychain.dto.request.ReplenishmentRuleRequest;
import com.sc.supplychain.entity.ReplenishmentOrder;
import com.sc.supplychain.entity.ReplenishmentRule;
import com.sc.supplychain.enums.ReplenishmentStatus;
import com.sc.supplychain.exception.SupplyChainException;
import com.sc.supplychain.mapper.ReplenishmentOrderMapper;
import com.sc.supplychain.mapper.ReplenishmentRuleMapper;
import com.sc.supplychain.mq.InventoryEventProducer;
import com.sc.supplychain.service.InventoryService;
import com.sc.supplychain.service.ReplenishmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReplenishmentServiceImpl implements ReplenishmentService {

    private final ReplenishmentRuleMapper ruleMapper;
    private final ReplenishmentOrderMapper orderMapper;
    private final InventoryService inventoryService;
    private final InventoryEventProducer producer;

    @Override
    public Long createRule(ReplenishmentRuleRequest req) {
        ReplenishmentRule rule = new ReplenishmentRule();
        rule.setSkuId(req.getSkuId());
        rule.setWarehouseId(req.getWarehouseId());
        rule.setMinQty(req.getMinQty());
        rule.setReplenishQty(req.getReplenishQty());
        rule.setSupplierId(req.getSupplierId());
        rule.setIsEnabled(1);
        ruleMapper.insert(rule);
        log.info("创建补货规则 ruleId={} skuId={} minQty={}", rule.getId(), rule.getSkuId(), rule.getMinQty());
        return rule.getId();
    }

    @Override
    public void checkAndTrigger() {
        List<ReplenishmentRule> rules = ruleMapper.selectAllEnabled();
        int triggered = 0;
        for (ReplenishmentRule rule : rules) {
            long available = inventoryService.getAvailableFromRedis(rule.getWarehouseId(), rule.getSkuId());
            if (available < rule.getMinQty()) {
                log.info("[补货触发] skuId={} warehouseId={} 当前={} 阈值={}",
                        rule.getSkuId(), rule.getWarehouseId(), available, rule.getMinQty());
                ReplenishmentMessage msg = new ReplenishmentMessage(
                        rule.getId(), rule.getSkuId(), rule.getWarehouseId(),
                        (int) available, rule.getReplenishQty(), System.currentTimeMillis());
                producer.sendReplenishmentTrigger(msg);
                triggered++;
            }
        }
        log.info("[补货扫描完成] 触发{}条规则，共扫描{}条", triggered, rules.size());
    }

    @Override
    public List<ReplenishmentOrder> listOrders(String status) {
        if (status == null) return orderMapper.selectList(null);
        return orderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReplenishmentOrder>()
                        .eq(ReplenishmentOrder::getStatus, status)
                        .orderByDesc(ReplenishmentOrder::getCreateTime));
    }

    @Override
    public void confirmOrder(Long orderId) {
        ReplenishmentOrder order = orderMapper.selectById(orderId);
        if (order == null) throw SupplyChainException.notFound("补货单", orderId);
        if (!ReplenishmentStatus.PENDING.getCode().equals(order.getStatus())) {
            throw SupplyChainException.illegalStatus("只有 PENDING 状态的补货单才能确认");
        }
        order.setStatus(ReplenishmentStatus.CONFIRMED.getCode());
        order.setConfirmTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("补货单[{}] 已确认", orderId);
    }
}
