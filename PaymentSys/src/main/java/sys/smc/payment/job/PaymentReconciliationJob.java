package sys.smc.payment.job;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Snowflake;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.entity.PaymentReconciliation;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.gateway.PaymentGateway;
import sys.smc.payment.gateway.PaymentGatewayRouter;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;
import sys.smc.payment.mapper.PaymentReconciliationMapper;
import sys.smc.payment.mapper.PaymentTransactionMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 支付对账定时任务
 *
 * 修复记录：
 *  ① ShedLock：防止3个实例同时执行对账，导致数据重复
 *  ② 注入 Snowflake Bean：防止多实例 workerId=0 产生相同 ID
 *  ④ 多渠道对账：通过 PaymentGatewayRouter 路由到对应网关，不再写死渣打银行
 *  ⑦ 乐观锁安全：ShedLock 保证单实例运行，无并发对账冲突
 */
@Component
@Slf4j
public class PaymentReconciliationJob {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    // ④ 修复：使用 Router 替代写死的 StandardCharteredGatewayClient，支持所有渠道
    @Autowired
    private PaymentGatewayRouter gatewayRouter;

    @Autowired
    private PaymentReconciliationMapper reconciliationMapper;

    // ② 修复：注入配置好 workerId 的 Snowflake bean，不再用 IdUtil.getSnowflakeNextIdStr()
    @Autowired
    private Snowflake snowflake;

    /**
     * 每30分钟自动对账 TIMEOUT 和 PENDING 交易
     *
     * ① @SchedulerLock：同一时间只有一个实例执行
     *   lockAtMostFor="25m"：最多持锁25分钟（比cron间隔30分钟短，防止上次未完成新任务启动）
     *   lockAtLeastFor="10m"：至少持锁10分钟（防止任务太快完成后其他实例立刻抢锁重跑）
     */
    @Scheduled(cron = "${payment.reconciliation.cron:0 */30 * * * ?}")
    @SchedulerLock(name = "reconcileTimeoutTransactions",
                   lockAtMostFor = "PT25M",
                   lockAtLeastFor = "PT5M")
    @Transactional(rollbackFor = Exception.class)
    public void reconcileTimeoutTransactions() {
        log.info("=== 开始自动对账TIMEOUT/PENDING交易（ShedLock已获取，单实例执行）===");
        Date startTime = new Date();
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
        int matched = 0, mismatch = 0, failed = 0;
        List<String> mismatchIds = new ArrayList<>();

        for (PaymentTransaction transaction : timeoutTransactions) {
            try {
                // ④ 修复：根据 gatewayName 路由到对应渠道（渣打/支付宝/建行）
                GatewayTransactionStatus gatewayStatus = queryGatewayStatus(transaction);
                if (gatewayStatus == null) {
                    failed++;
                    continue;
                }

                transaction.setLastQueryTime(new Date());

                if (!transaction.getPaymentStatus().equals(gatewayStatus.getStatus())) {
                    log.warn("⚠️ 状态不匹配 - 交易:{}, 本地:{}, 银行:{}",
                        transaction.getTransactionId(),
                        transaction.getPaymentStatus(),
                        gatewayStatus.getStatus());

                    transaction.setPreviousStatus(transaction.getPaymentStatus());
                    transaction.setPaymentStatus(gatewayStatus.getStatus());
                    transaction.setReconciliationStatus("MISMATCH");
                    transaction.setReconciliationTime(new Date());
                    transaction.setRemarks("对账修正：" + transaction.getPreviousStatus()
                        + " -> " + gatewayStatus.getStatus());

                    if (PaymentStatus.SUCCESS.name().equals(gatewayStatus.getStatus())) {
                        log.error("🚨 严重：支付成功但标记为TIMEOUT，交易ID：{}",
                            transaction.getTransactionId());
                        // TODO: 发送告警 + 通知订单系统
                    }
                    mismatch++;
                    mismatchIds.add(transaction.getTransactionId());
                } else {
                    transaction.setReconciliationStatus("MATCHED");
                    matched++;
                }
                transactionMapper.updateById(transaction);

            } catch (Exception e) {
                log.error("对账失败，交易ID：{}", transaction.getTransactionId(), e);
                failed++;
            }
        }

        // ② 修复：用注入的 snowflake bean 生成 ID
        PaymentReconciliation rec = buildReconciliation(
            "AUTO_TIMEOUT", timeoutTransactions.size(), matched, mismatch, 0, 0,
            mismatchIds, startTime);
        reconciliationMapper.insert(rec);

        log.info("=== 对账完成 - 总:{}, 匹配:{}, 不匹配:{}, 失败:{} ===",
            timeoutTransactions.size(), matched, mismatch, failed);
    }

    /**
     * 每日凌晨3点全量对账
     *
     * ① @SchedulerLock：只有一个实例运行，防止3倍重复
     */
    @Scheduled(cron = "${payment.reconciliation.daily-cron:0 0 3 * * ?}")
    @SchedulerLock(name = "dailyFullReconciliation",
                   lockAtMostFor = "PT2H",
                   lockAtLeastFor = "PT10M")
    @Transactional(rollbackFor = Exception.class)
    public void dailyFullReconciliation() {
        log.info("=== 开始每日全量对账（ShedLock已获取，单实例执行）===");
        Date startTime = new Date();
        Date yesterday = DateUtil.offsetDay(new Date(), -1);

        List<PaymentTransaction> transactions = transactionMapper.selectList(
            new LambdaQueryWrapper<PaymentTransaction>()
                .between(PaymentTransaction::getCreateTime,
                    DateUtil.beginOfDay(yesterday),
                    DateUtil.endOfDay(yesterday))
                .orderByAsc(PaymentTransaction::getCreateTime)
        );

        log.info("昨日交易总数：{}", transactions.size());
        int matched = 0, mismatch = 0, timeoutCount = 0, pendingCount = 0;
        List<String> mismatchIds = new ArrayList<>();

        for (PaymentTransaction transaction : transactions) {
            try {
                if (PaymentStatus.TIMEOUT.name().equals(transaction.getPaymentStatus())) timeoutCount++;
                if (PaymentStatus.PENDING.name().equals(transaction.getPaymentStatus())) pendingCount++;

                if (!isTerminalStatus(transaction.getPaymentStatus())
                        && transaction.getGatewayTransactionId() != null) {

                    // ④ 修复：多渠道路由
                    GatewayTransactionStatus gatewayStatus = queryGatewayStatus(transaction);
                    if (gatewayStatus == null) continue;

                    if (!transaction.getPaymentStatus().equals(gatewayStatus.getStatus())) {
                        mismatch++;
                        mismatchIds.add(transaction.getTransactionId());
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

        PaymentReconciliation rec = buildReconciliation(
            "DAILY", transactions.size(), matched, mismatch,
            timeoutCount, pendingCount, mismatchIds, startTime);
        reconciliationMapper.insert(rec);

        log.info("=== 每日全量对账完成 - 总:{}, 匹配:{}, 不匹配:{} ===",
            transactions.size(), matched, mismatch);
        // TODO: 生成对账报告并发送给财务团队
    }

    /**
     * ④ 修复：根据交易的 gatewayName 路由到对应渠道查询状态
     * 原来写死了 StandardCharteredGatewayClient，支付宝/建行永远查不了
     */
    private GatewayTransactionStatus queryGatewayStatus(PaymentTransaction transaction) {
        try {
            PaymentChannel channel = PaymentChannel.fromCode(transaction.getGatewayName());
            PaymentGateway gateway = gatewayRouter.getGateway(channel);
            return gateway.queryTransactionStatus(transaction.getGatewayTransactionId());
        } catch (Exception e) {
            log.error("查询[{}]渠道状态失败，交易ID：{}",
                transaction.getGatewayName(), transaction.getTransactionId(), e);
            return null;
        }
    }

    /** ② 修复：统一使用 snowflake bean 构建对账记录 */
    private PaymentReconciliation buildReconciliation(
            String type, int total, int matched, int mismatch,
            int timeoutCnt, int pendingCnt, List<String> mismatchIds, Date startTime) {

        PaymentReconciliation rec = new PaymentReconciliation();
        rec.setId(snowflake.nextId());                          // ← 注入的bean
        rec.setReconciliationDate(new Date());
        rec.setReconciliationType(type);
        rec.setTotalTransactions(total);
        rec.setMatchedCount(matched);
        rec.setMismatchCount(mismatch);
        rec.setTimeoutCount(timeoutCnt);
        rec.setPendingCount(pendingCnt);
        rec.setMismatchTransactionIds(JSON.toJSONString(mismatchIds));
        rec.setReconciliationStatus("COMPLETED");
        rec.setStartTime(startTime);
        rec.setEndTime(new Date());
        rec.setCreateUser("SYSTEM");
        return rec;
    }

    private boolean isTerminalStatus(String status) {
        return PaymentStatus.SUCCESS.name().equals(status)
            || PaymentStatus.FAILED.name().equals(status)
            || PaymentStatus.REFUNDED.name().equals(status);
    }
}

