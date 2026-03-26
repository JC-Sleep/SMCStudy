package sys.smc.payment.job;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.entity.PaymentReconciliation;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.gateway.StandardCharteredGatewayClient;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;
import sys.smc.payment.mapper.PaymentReconciliationMapper;
import sys.smc.payment.mapper.PaymentTransactionMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 支付对账定时任务
 * 核心功能：防止"客户已付款但系统显示超时"的问题
 */
@Component
@Slf4j
public class PaymentReconciliationJob {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private StandardCharteredGatewayClient gatewayClient;

    @Autowired
    private PaymentReconciliationMapper reconciliationMapper;

    /**
     * 每30分钟自动对账TIMEOUT和PENDING交易
     * 这是防止支付差异的关键机制
     */
    @Scheduled(cron = "${payment.reconciliation.cron:0 */30 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void reconcileTimeoutTransactions() {
        log.info("=== 开始自动对账TIMEOUT/PENDING交易 ===");

        Date startTime = new Date();

        // 查找超过30分钟的TIMEOUT或PENDING交易
        Date cutoffTime = DateUtil.offsetMinute(new Date(), -30);

        List<PaymentTransaction> timeoutTransactions = transactionMapper.selectList(
            new LambdaQueryWrapper<PaymentTransaction>()
                .in(PaymentTransaction::getPaymentStatus,
                    PaymentStatus.TIMEOUT.name(),
                    PaymentStatus.PENDING.name())
                .lt(PaymentTransaction::getStatusUpdateTime, cutoffTime)
                .orderByAsc(PaymentTransaction::getCreateTime)
        );

        log.info("找到 {} 笔需要对账的交易", timeoutTransactions.size());

        int matched = 0;
        int mismatch = 0;
        int failed = 0;
        List<String> mismatchIds = new ArrayList<>();

        for (PaymentTransaction transaction : timeoutTransactions) {
            try {
                log.info("对账交易：{}，本地状态：{}",
                    transaction.getTransactionId(),
                    transaction.getPaymentStatus());

                // 查询渣打银行的实际状态
                GatewayTransactionStatus gatewayStatus = gatewayClient.queryTransactionStatus(
                    transaction.getGatewayTransactionId()
                );

                transaction.setLastQueryTime(new Date());

                // 比较状态
                if (!transaction.getPaymentStatus().equals(gatewayStatus.getStatus())) {
                    log.warn("⚠️ 发现状态不匹配 - 交易: {}, 本地: {}, 银行: {}",
                        transaction.getTransactionId(),
                        transaction.getPaymentStatus(),
                        gatewayStatus.getStatus());

                    // 更新为银行状态（银行是真实来源）
                    transaction.setPreviousStatus(transaction.getPaymentStatus());
                    transaction.setPaymentStatus(gatewayStatus.getStatus());
                    transaction.setReconciliationStatus("MISMATCH");
                    transaction.setReconciliationTime(new Date());
                    transaction.setRemarks("对账修正：" + transaction.getPreviousStatus() + " -> " + gatewayStatus.getStatus());

                    // 如果银行说成功但我们标记为TIMEOUT，立即告警
                    if (PaymentStatus.SUCCESS.name().equals(gatewayStatus.getStatus())) {
                        log.error("🚨 严重：支付成功但标记为TIMEOUT，交易ID：{}", transaction.getTransactionId());
                        // TODO: 发送告警邮件
                        // TODO: 通知订单系统
                    }

                    mismatch++;
                    mismatchIds.add(transaction.getTransactionId());
                } else {
                    transaction.setReconciliationStatus("MATCHED");
                    matched++;
                }

                transactionMapper.updateById(transaction);

            } catch (Exception e) {
                log.error("对账交易失败，交易ID：{}", transaction.getTransactionId(), e);
                failed++;
            }
        }

        // 保存对账记录
        PaymentReconciliation reconciliation = new PaymentReconciliation();
        reconciliation.setId(Long.valueOf(IdUtil.getSnowflakeNextIdStr()));
        reconciliation.setReconciliationDate(new Date());
        reconciliation.setReconciliationType("AUTO_TIMEOUT");
        reconciliation.setTotalTransactions(timeoutTransactions.size());
        reconciliation.setMatchedCount(matched);
        reconciliation.setMismatchCount(mismatch);
        reconciliation.setTimeoutCount(0);
        reconciliation.setPendingCount(0);
        reconciliation.setMismatchTransactionIds(JSON.toJSONString(mismatchIds));
        reconciliation.setReconciliationStatus("COMPLETED");
        reconciliation.setStartTime(startTime);
        reconciliation.setEndTime(new Date());
        reconciliation.setCreateUser("SYSTEM");

        reconciliationMapper.insert(reconciliation);

        log.info("=== 对账完成 - 总数: {}, 匹配: {}, 不匹配: {}, 失败: {} ===",
            timeoutTransactions.size(), matched, mismatch, failed);
    }

    /**
     * 每日凌晨3点全量对账
     */
    @Scheduled(cron = "${payment.reconciliation.daily-cron:0 0 3 * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void dailyFullReconciliation() {
        log.info("=== 开始每日全量对账 ===");

        Date startTime = new Date();
        Date yesterday = DateUtil.offsetDay(new Date(), -1);
        Date yesterdayStart = DateUtil.beginOfDay(yesterday);
        Date yesterdayEnd = DateUtil.endOfDay(yesterday);

        // 查询昨天所有交易
        List<PaymentTransaction> transactions = transactionMapper.selectList(
            new LambdaQueryWrapper<PaymentTransaction>()
                .between(PaymentTransaction::getCreateTime, yesterdayStart, yesterdayEnd)
                .orderByAsc(PaymentTransaction::getCreateTime)
        );

        log.info("昨日交易总数：{}", transactions.size());

        int matched = 0;
        int mismatch = 0;
        int timeoutCount = 0;
        int pendingCount = 0;
        List<String> mismatchIds = new ArrayList<>();

        for (PaymentTransaction transaction : transactions) {
            try {
                // 统计各状态交易数
                if (PaymentStatus.TIMEOUT.name().equals(transaction.getPaymentStatus())) {
                    timeoutCount++;
                }
                if (PaymentStatus.PENDING.name().equals(transaction.getPaymentStatus())) {
                    pendingCount++;
                }

                // 对于非终态交易，查询银行状态
                if (!isTerminalStatus(transaction.getPaymentStatus()) &&
                    transaction.getGatewayTransactionId() != null) {

                    GatewayTransactionStatus gatewayStatus = gatewayClient.queryTransactionStatus(
                        transaction.getGatewayTransactionId()
                    );

                    if (!transaction.getPaymentStatus().equals(gatewayStatus.getStatus())) {
                        mismatch++;
                        mismatchIds.add(transaction.getTransactionId());

                        // 更新状态
                        transaction.setPreviousStatus(transaction.getPaymentStatus());
                        transaction.setPaymentStatus(gatewayStatus.getStatus());
                        transaction.setReconciliationStatus("MISMATCH");
                        transaction.setReconciliationTime(new Date());
                        transactionMapper.updateById(transaction);
                    } else {
                        matched++;
                    }
                } else {
                    matched++;
                }

            } catch (Exception e) {
                log.error("全量对账失败，交易ID：{}", transaction.getTransactionId(), e);
            }
        }

        // 保存对账记录
        PaymentReconciliation reconciliation = new PaymentReconciliation();
        reconciliation.setId(Long.valueOf(IdUtil.getSnowflakeNextIdStr()));
        reconciliation.setReconciliationDate(new Date());
        reconciliation.setReconciliationType("DAILY");
        reconciliation.setTotalTransactions(transactions.size());
        reconciliation.setMatchedCount(matched);
        reconciliation.setMismatchCount(mismatch);
        reconciliation.setTimeoutCount(timeoutCount);
        reconciliation.setPendingCount(pendingCount);
        reconciliation.setMismatchTransactionIds(JSON.toJSONString(mismatchIds));
        reconciliation.setReconciliationStatus("COMPLETED");
        reconciliation.setStartTime(startTime);
        reconciliation.setEndTime(new Date());
        reconciliation.setCreateUser("SYSTEM");

        reconciliationMapper.insert(reconciliation);

        log.info("=== 每日全量对账完成 - 总数: {}, 匹配: {}, 不匹配: {}, TIMEOUT: {}, PENDING: {} ===",
            transactions.size(), matched, mismatch, timeoutCount, pendingCount);

        // TODO: 生成对账报告并发送给财务团队
    }

    /**
     * 判断是否为终态
     */
    private boolean isTerminalStatus(String status) {
        return PaymentStatus.SUCCESS.name().equals(status) ||
               PaymentStatus.FAILED.name().equals(status) ||
               PaymentStatus.REFUNDED.name().equals(status);
    }
}

