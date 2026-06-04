package com.sc.supplychain.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sc.supplychain.dto.request.OrderCreateRequest;
import com.sc.supplychain.entity.FulfillmentOrder;
import com.sc.supplychain.entity.FulfillmentRecord;
import com.sc.supplychain.enums.FulfillmentOrderStatus;
import com.sc.supplychain.exception.SupplyChainException;
import com.sc.supplychain.mapper.FulfillmentOrderMapper;
import com.sc.supplychain.mapper.FulfillmentRecordMapper;
import com.sc.supplychain.service.FulfillmentService;
import com.sc.supplychain.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentServiceImpl implements FulfillmentService {

    private final FulfillmentOrderMapper orderMapper;
    private final FulfillmentRecordMapper recordMapper;
    private final InventoryService inventoryService;

    /** 默认主仓 ID（生产应从配置或DB读取） */
    private static final Long DEFAULT_WAREHOUSE_ID = 1L;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createOrder(OrderCreateRequest req) {
        Long warehouseId = req.getWarehouseId() != null ? req.getWarehouseId() : DEFAULT_WAREHOUSE_ID;
        String orderNo = "ORD-" + IdUtil.fastSimpleUUID().toUpperCase().substring(0, 16);

        // 1. Redis 预扣（Lua Script，库存不足抛异常）
        inventoryService.lockStock(warehouseId, req.getSkuId(), req.getQty(), orderNo);

        // 2. 写订单 DB
        FulfillmentOrder order = new FulfillmentOrder();
        order.setOrderNo(orderNo);
        order.setStoreId(req.getStoreId());
        order.setWarehouseId(warehouseId);   // persist so cancel/outbound use same warehouse
        order.setSkuId(req.getSkuId());
        order.setQty(req.getQty());
        order.setStatus(FulfillmentOrderStatus.PENDING.getCode());
        order.setPayAmount(req.getPayAmount());
        order.setAddress(req.getAddress());
        orderMapper.insert(order);

        // 3. 写流水
        writeRecord(order.getId(), "CREATE", "system", "O2O下单");
        log.info("[下单] orderNo={} skuId={} qty={}", orderNo, req.getSkuId(), req.getQty());
        return orderNo;
    }

    /**
     * 取消订单。
     * 【B3 修复】用条件 UPDATE 抢占式取消，affected=0 直接退出，绝不会双倍释放库存。
     * 【B4 修复】状态白名单收紧到 PENDING / PAID，OUTBOUND/PICKING/DELIVERED/CANCELLED 全部禁止取消。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderNo) {
        FulfillmentOrder order = requireOrder(orderNo);

        // ───── B4：白名单校验（先做友好提示，再做原子保护）─────
        String status = order.getStatus();
        if (!FulfillmentOrderStatus.PENDING.getCode().equals(status)
                && !FulfillmentOrderStatus.PAID.getCode().equals(status)) {
            throw SupplyChainException.illegalStatus(
                    "订单当前状态=" + status + "，仅 PENDING/PAID 可取消");
        }

        // ───── B3：原子条件 UPDATE 抢占取消权 ─────
        int affected = orderMapper.cancelIfCancelable(orderNo);
        if (affected == 0) {
            // 并发场景下另一个线程已成功取消（或状态被其他流程改走）
            log.warn("[取消][并发抢占失败] orderNo={} 已被其他线程处理或状态已变更，跳过释放", orderNo);
            return;
        }

        // ───── 抢到取消权：释放预扣库存 ─────
        Long whId = order.getWarehouseId() != null ? order.getWarehouseId() : DEFAULT_WAREHOUSE_ID;
        inventoryService.unlockStock(whId, order.getSkuId(), order.getQty(), orderNo);
        writeRecord(order.getId(), "CANCEL", "system", "订单取消，库存归还");
        log.info("[取消] orderNo={}", orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onPaid(String orderNo) {
        FulfillmentOrder order = requireOrder(orderNo);
        if (!FulfillmentOrderStatus.PENDING.getCode().equals(order.getStatus())) {
            throw SupplyChainException.illegalStatus("只有 PENDING 订单才能支付");
        }
        order.setStatus(FulfillmentOrderStatus.PAID.getCode());
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);
        writeRecord(order.getId(), "PAY", "system", "支付成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void outbound(String orderNo) {
        FulfillmentOrder order = requireOrder(orderNo);
        if (!FulfillmentOrderStatus.PAID.getCode().equals(order.getStatus())) {
            throw SupplyChainException.illegalStatus("只有 PAID 订单才能出库");
        }
        // FIFO 分配批次 + 出库确认 — use order's warehouse
        Long whId = order.getWarehouseId() != null ? order.getWarehouseId() : DEFAULT_WAREHOUSE_ID;
        inventoryService.confirmDeduct(whId, order.getSkuId(), order.getQty(), orderNo);
        order.setStatus(FulfillmentOrderStatus.OUTBOUND.getCode());
        orderMapper.updateById(order);
        writeRecord(order.getId(), "OUTBOUND", "system", "FIFO出库确认");
        log.info("[出库] orderNo={}", orderNo);
    }

    @Override
    public List<FulfillmentOrder> listByStore(Long storeId) {
        return orderMapper.selectList(new LambdaQueryWrapper<FulfillmentOrder>()
                .eq(FulfillmentOrder::getStoreId, storeId)
                .orderByDesc(FulfillmentOrder::getCreateTime));
    }

    @Override
    public FulfillmentOrder getByOrderNo(String orderNo) {
        return requireOrder(orderNo);
    }

    private FulfillmentOrder requireOrder(String orderNo) {
        FulfillmentOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOrder>()
                        .eq(FulfillmentOrder::getOrderNo, orderNo));
        if (order == null) throw SupplyChainException.notFound("订单", orderNo);
        return order;
    }

    private void writeRecord(Long orderId, String opType, String opBy, String remark) {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setOrderId(orderId);
        record.setOpType(opType);
        record.setOpBy(opBy);
        record.setRemark(remark);
        recordMapper.insert(record);
    }
}
