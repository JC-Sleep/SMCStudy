package sys.smc.coupon.listener;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import sys.smc.coupon.config.ActiveMQConfig;
import sys.smc.coupon.entity.Coupon;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.service.CouponService;

import java.math.BigDecimal;

/**
 * 消费事件监听器
 * 监听计费系统发送的消费事件，判断是否触发消费激励型优惠券发放
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumptionEventListener {

    private final CouponService couponService;

    // 消费激励阈值配置（实际应从数据库规则表读取）
    private static final BigDecimal MONTHLY_THRESHOLD = new BigDecimal("500");
    private static final Long CONSUMPTION_REWARD_TEMPLATE_ID = 1001L;

    /**
     * 监听消费事件
     * 消息格式示例:
     * {
     *   "userId": "U123456",
     *   "userMobile": "85212345678",
     *   "amount": 100.00,
     *   "consumeType": "VOICE",
     *   "monthlyTotal": 520.00,
     *   "consumeTime": "2026-03-25 10:30:00"
     * }
     */
    @JmsListener(destination = ActiveMQConfig.CONSUMPTION_EVENT_QUEUE)
    public void onConsumptionEvent(String message) {
        log.info("收到消费事件: {}", message);

        try {
            JSONObject event = JSON.parseObject(message);
            String userId = event.getString("userId");
            String userMobile = event.getString("userMobile");
            BigDecimal monthlyTotal = event.getBigDecimal("monthlyTotal");

            // 检查是否达到消费激励阈值
            if (monthlyTotal != null && monthlyTotal.compareTo(MONTHLY_THRESHOLD) >= 0) {
                // 检查本月是否已发放过
                // TODO: 增加发放记录检查，避免重复发放
                
                log.info("用户达到消费激励阈值，发放优惠券: userId={}, monthlyTotal={}", 
                        userId, monthlyTotal);
                
                Coupon coupon = couponService.grantCoupon(
                        userId,
                        userMobile,
                        CONSUMPTION_REWARD_TEMPLATE_ID,
                        GrantType.CONSUMPTION.getCode(),
                        "CONSUMPTION-" + event.getString("consumeTime")
                );
                
                log.info("消费激励优惠券发放成功: userId={}, couponCode={}", 
                        userId, coupon.getCouponCode());
            }

        } catch (Exception e) {
            log.error("处理消费事件失败: message={}", message, e);
        }
    }
}

