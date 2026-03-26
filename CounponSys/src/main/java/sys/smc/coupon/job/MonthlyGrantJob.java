package sys.smc.coupon.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sys.smc.coupon.config.ActiveMQConfig;
import sys.smc.coupon.entity.CouponTemplate;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.service.CouponTemplateService;

import java.util.List;

/**
 * 月度套餐赠送定时任务
 * 每月1号凌晨2点执行，为绑定套餐的用户发放优惠券
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyGrantJob {

    private final CouponTemplateService templateService;
    private final JmsTemplate jmsTemplate;

    /**
     * 每月1号凌晨2点执行
     * Cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 1 * ?")
    public void executeMonthlyGrant() {
        log.info("========== 开始执行月度套餐赠送任务 ==========");
        
        try {
            // 1. 查询所有启用的套餐赠送类型模板
            List<CouponTemplate> templates = templateService.listEnabledByGrantType(
                    GrantType.PACKAGE_MONTHLY.getCode());
            
            if (templates.isEmpty()) {
                log.info("没有启用的套餐赠送模板，跳过执行");
                return;
            }

            log.info("找到 {} 个套餐赠送模板", templates.size());

            // 2. 为每个模板发送MQ消息，异步处理
            for (CouponTemplate template : templates) {
                // 检查库存
                int remainStock = template.getTotalQuantity() - template.getIssuedQuantity();
                if (remainStock <= 0) {
                    log.warn("模板库存不足，跳过: templateId={}, name={}", 
                            template.getId(), template.getName());
                    continue;
                }

                // 发送MQ消息，由消费者处理具体的发放逻辑
                MonthlyGrantMessage message = new MonthlyGrantMessage();
                message.setTemplateId(template.getId());
                message.setTemplateName(template.getName());
                message.setApplicablePackages(template.getApplicablePackages());
                
                jmsTemplate.convertAndSend(ActiveMQConfig.COUPON_GRANT_QUEUE, message);
                
                log.info("已发送月度赠送任务: templateId={}, name={}", 
                        template.getId(), template.getName());
            }

            log.info("========== 月度套餐赠送任务发送完成 ==========");

        } catch (Exception e) {
            log.error("月度套餐赠送任务执行失败", e);
        }
    }

    /**
     * 月度赠送消息体
     */
    @lombok.Data
    public static class MonthlyGrantMessage implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        private Long templateId;
        private String templateName;
        /** 适用的套餐编码，逗号分隔 */
        private String applicablePackages;
    }
}

