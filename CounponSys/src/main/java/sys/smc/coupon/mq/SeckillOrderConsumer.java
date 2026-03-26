package sys.smc.coupon.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.coupon.config.ActiveMQConfig;
import sys.smc.coupon.entity.Coupon;
import sys.smc.coupon.entity.SeckillActivity;
import sys.smc.coupon.entity.SeckillOrder;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.mapper.SeckillActivityMapper;
import sys.smc.coupon.mapper.SeckillOrderMapper;
import sys.smc.coupon.service.CouponService;
import sys.smc.coupon.service.PointsService;

/**
 * 秒杀订单消息消费者
 * 异步处理：扣积分 -> 发券 -> 更新订单状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final CouponService couponService;
    private final PointsService pointsService;

    @JmsListener(destination = ActiveMQConfig.SECKILL_ORDER_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void processSeckillOrder(String orderIdStr) {
        Long orderId = Long.parseLong(orderIdStr);
        log.info("处理秒杀订单: orderId={}", orderId);

        SeckillOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus() != 0) {
            log.warn("订单不存在或状态异常: orderId={}", orderId);
            return;
        }

        try {
            // 1. 扣减积分(如果需要)
            if (order.getPointsUsed() != null && order.getPointsUsed() > 0) {
                boolean deducted = pointsService.deductPoints(
                        order.getUserId(),
                        order.getPointsChannel(),
                        order.getPointsUsed(),
                        "秒杀抢券-" + order.getOrderNo()
                );
                if (!deducted) {
                    handleOrderFail(order, "积分扣减失败");
                    return;
                }
            }

            // 2. 获取活动信息
            SeckillActivity activity = activityMapper.selectById(order.getActivityId());
            if (activity == null) {
                handleOrderFail(order, "活动不存在");
                return;
            }

            // 3. 发放优惠券
            Coupon coupon = couponService.grantCoupon(
                    order.getUserId(),
                    order.getUserMobile(),
                    activity.getTemplateId(),
                    GrantType.SECKILL.getCode(),
                    "SECKILL-" + order.getActivityId()
            );

            // 4. 更新订单状态为成功
            order.setCouponId(coupon.getId());
            order.setStatus(1); // 成功
            orderMapper.updateById(order);

            // 5. 同步数据库库存
            activityMapper.deductStock(order.getActivityId(), 1);

            log.info("秒杀订单处理成功: orderId={}, couponCode={}", orderId, coupon.getCouponCode());

        } catch (Exception e) {
            log.error("秒杀订单处理失败: orderId={}", orderId, e);
            handleOrderFail(order, e.getMessage());
            // 回滚积分
            if (order.getPointsUsed() != null && order.getPointsUsed() > 0) {
                pointsService.rollbackPoints(
                        order.getUserId(),
                        order.getPointsChannel(),
                        order.getPointsUsed(),
                        "秒杀失败回滚-" + order.getOrderNo()
                );
            }
            throw e;
        }
    }

    private void handleOrderFail(SeckillOrder order, String reason) {
        order.setStatus(2); // 失败
        order.setFailReason(reason);
        orderMapper.updateById(order);
        log.warn("秒杀订单失败: orderId={}, reason={}", order.getId(), reason);
    }
}

