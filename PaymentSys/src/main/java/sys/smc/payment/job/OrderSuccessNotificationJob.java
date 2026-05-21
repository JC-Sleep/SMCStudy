package sys.smc.payment.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.mapper.PaymentTransactionMapper;

import java.util.List;

/**
 * 支付成功通知补偿 Job（死账消灭的最终防线）
 *
 * ─── 解决的核心问题 ────────────────────────────────────────────────────────────
 *
 * 问题场景（CSP001xxxxx 类型死账）：
 *   ① 用户付款 → 银行扣款成功
 *   ② 银行回调丢失（网络问题）或系统崩溃
 *   ③ 系统标记为 TIMEOUT → 对账 Job 修正为 SUCCESS
 *   ④ 订单系统无感知 → 订单永远 UNPAID → 用户付了钱但收不到服务
 *
 * 现有防线：
 *   防线1: PaymentCallbackServiceEnhanced.notifyOrderSystem() - 回调成功后立即通知，
 *          成功设 ORDER_NOTIFIED=1，失败则 ORDER_NOTIFIED 保持 0。
 *   防线2（本 Job）: 定期扫描 ORDER_NOTIFIED=0 的 SUCCESS 交易，兜底重试通知。
 *          解决防线1失败的场景，以及对账修正后的通知场景。
 *
 * ─── ORDER_NOTIFIED 字段生命周期 ──────────────────────────────────────────────
 *
 *   支付成功（回调或对账修正）→ ORDER_NOTIFIED = 0（待通知）
 *   本 Job 通知成功            → ORDER_NOTIFIED = 1（已通知，不再扫描）
 *   本 Job 通知失败            → ORDER_NOTIFIED 保持 0（下次重试）
 *
 * ─── 幂等性保证 ───────────────────────────────────────────────────────────────
 *
 *   订单系统必须实现幂等接口（根据 transactionId 去重），
 *   防止本 Job 重试时重复触发发货/开通套餐等操作。
 *
 * DDL（若 ORDER_NOTIFIED 字段尚未添加）：
 *   ALTER TABLE PAYMENT_TRANSACTION ADD ORDER_NOTIFIED NUMBER(1) DEFAULT 0;
 *   CREATE INDEX IDX_PTX_ORDER_NOTIFIED ON PAYMENT_TRANSACTION(PAYMENT_STATUS, ORDER_NOTIFIED);
 */
@Component
@Slf4j
public class OrderSuccessNotificationJob {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    /**
     * 每 5 分钟扫描 SUCCESS 且 ORDER_NOTIFIED=0 的交易，重试通知订单系统
     *
     * ShedLock：lockAtMostFor="4m"（比 cron 间隔 5 分钟短），防止前一轮未完成时新轮启动
     */
    @Scheduled(cron = "${payment.order-notify.cron:0 */5 * * * ?}")
    @SchedulerLock(name = "orderSuccessNotificationJob",
                   lockAtMostFor = "PT4M",
                   lockAtLeastFor = "PT1M")
    public void retryPendingNotifications() {
        log.debug("[订单通知Job] 开始扫描未通知的成功支付...");

        // 查找 STATUS=SUCCESS 且 ORDER_NOTIFIED=0（未通知或通知失败）
        // 限制 100 条/次防止大批量阻塞
        List<PaymentTransaction> pending = transactionMapper.selectList(
            new LambdaQueryWrapper<PaymentTransaction>()
                .eq(PaymentTransaction::getPaymentStatus, PaymentStatus.SUCCESS.name())
                .eq(PaymentTransaction::getOrderNotified, 0)
                .orderByAsc(PaymentTransaction::getStatusUpdateTime)
                .last("FETCH FIRST 100 ROWS ONLY")   // Oracle 语法；MySQL 改为 LIMIT 100
        );

        if (pending.isEmpty()) {
            log.debug("[订单通知Job] 无待通知交易");
            return;
        }

        log.warn("[订单通知Job] 发现 {} 笔未通知订单系统的成功支付，开始重试", pending.size());

        int success = 0, failed = 0;
        for (PaymentTransaction txn : pending) {
            boolean notified = sendNotification(txn);
            if (notified) {
                success++;
            } else {
                failed++;
            }
        }

        log.info("[订单通知Job] 完成。成功={}, 失败={}/将在下轮重试", success, failed);
    }

    /**
     * 发送单笔通知，并在同一小事务中更新 ORDER_NOTIFIED。
     *
     * 使用 REQUIRES_NEW 独立事务：
     *   一笔失败不影响其他笔，且每笔成功后立即提交（避免持锁过久）。
     *
     * @return true=通知并更新成功，false=失败（下次重试）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean sendNotification(PaymentTransaction txn) {
        try {
            // ── 实际通知逻辑（以下三选一）────────────────────────────────────────
            // Option A（推荐）：MQ 发布（RocketMQ/Kafka，幂等消费）
            // mqTemplate.send("payment.success.topic", buildEvent(txn));

            // Option B（过渡）：同步 HTTP 调用订单服务（需设超时，订单服务需幂等）
            // orderServiceClient.confirmPayment(txn.getOrderReference(), txn.getTransactionId());

            // ── 当前：结构化告警日志（可被 ELK/Splunk 采集并触发告警）────────────
            // 生产上线前必须替换为 MQ 或 HTTP 调用！
            log.error("[订单通知] 🚨 PAYMENT_SUCCESS_NEEDS_ORDER_CONFIRM " +
                      "orderRef={} txnId={} amount={} currency={} gateway={} successAt={}",
                      txn.getOrderReference(),
                      txn.getTransactionId(),
                      txn.getAmount(),
                      txn.getCurrency(),
                      txn.getGatewayName(),
                      txn.getStatusUpdateTime());

            // 通知成功，标记为已通知（ORDER_NOTIFIED=1）
            // 不带 version：ORDER_NOTIFIED 字段更新不需要乐观锁（其他字段已稳定在 SUCCESS 终态）
            PaymentTransaction upd = new PaymentTransaction();
            upd.setId(txn.getId());
            upd.setOrderNotified(1);
            upd.setUpdateUser("ORDER_NOTIFY_JOB");
            transactionMapper.updateById(upd);

            log.info("[订单通知] ✅ 已通知，ORDER_NOTIFIED=1 txn={}", txn.getTransactionId());
            return true;

        } catch (Exception e) {
            log.error("[订单通知] ❌ 通知失败，将在下轮重试 txn={} error={}",
                      txn.getTransactionId(), e.getMessage());
            return false;
            // REQUIRES_NEW 事务：此笔失败不影响其他笔
        }
    }
}


