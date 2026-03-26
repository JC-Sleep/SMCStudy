package sys.smc.coupon.mq;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import sys.smc.coupon.config.ActiveMQConfig;
import sys.smc.coupon.entity.Coupon;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.job.MonthlyGrantJob.MonthlyGrantMessage;
import sys.smc.coupon.service.CouponService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 优惠券发放消息消费者
 * 处理月度套餐赠送等异步发放任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponGrantConsumer {

    private final CouponService couponService;
    // 注入用户服务（这里用模拟数据，实际需要对接用户/CRM系统）
    // private final UserService userService;

    @JmsListener(destination = ActiveMQConfig.COUPON_GRANT_QUEUE)
    public void processMonthlyGrant(MonthlyGrantMessage message) {
        log.info("收到月度赠送任务: templateId={}, name={}", 
                message.getTemplateId(), message.getTemplateName());

        try {
            // 1. 查询符合条件的用户列表
            // 实际项目中，需要对接CRM/用户系统，查询绑定了指定套餐的用户
            List<UserInfo> eligibleUsers = getEligibleUsers(message.getApplicablePackages());
            
            log.info("找到 {} 个符合条件的用户", eligibleUsers.size());

            int successCount = 0;
            int failCount = 0;
            String grantSource = "MONTHLY-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));

            // 2. 为每个用户发放优惠券
            for (UserInfo user : eligibleUsers) {
                try {
                    Coupon coupon = couponService.grantCoupon(
                            user.getUserId(),
                            user.getMobile(),
                            message.getTemplateId(),
                            GrantType.PACKAGE_MONTHLY.getCode(),
                            grantSource
                    );
                    successCount++;
                    log.debug("月度赠送成功: userId={}, couponCode={}", 
                            user.getUserId(), coupon.getCouponCode());
                    
                } catch (Exception e) {
                    failCount++;
                    log.warn("月度赠送失败: userId={}, error={}", user.getUserId(), e.getMessage());
                }
            }

            log.info("月度赠送任务完成: templateId={}, 成功={}, 失败={}", 
                    message.getTemplateId(), successCount, failCount);

        } catch (Exception e) {
            log.error("处理月度赠送任务失败: templateId={}", message.getTemplateId(), e);
        }
    }

    /**
     * 获取符合条件的用户列表
     * TODO: 实际项目中对接CRM/用户系统
     */
    private List<UserInfo> getEligibleUsers(String applicablePackages) {
        // 模拟数据 - 实际需要调用用户服务API
        // 例如: userService.listUsersByPackages(Arrays.asList(applicablePackages.split(",")))
        
        log.info("查询绑定套餐的用户: packages={}", applicablePackages);
        
        // 返回模拟用户
        return Arrays.asList(
                new UserInfo("U001", "85212345678"),
                new UserInfo("U002", "85212345679"),
                new UserInfo("U003", "85212345680")
        );
    }

    /**
     * 用户信息
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class UserInfo {
        private String userId;
        private String mobile;
    }
}

