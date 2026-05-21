package sys.smc.payment.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.payment.entity.PaymentTransaction;
import sys.smc.payment.enums.PaymentStatus;
import sys.smc.payment.exception.IllegalStateTransitionException;
import sys.smc.payment.exception.OptimisticLockException;
import sys.smc.payment.gateway.dto.GatewayTransactionStatus;
import sys.smc.payment.mapper.PaymentTransactionMapper;
import sys.smc.payment.statemachine.PaymentStateMachine;
import sys.smc.payment.statemachine.TransitionContext;

import java.util.Date;

import static sys.smc.payment.enums.PaymentStatus.*;

/**
 * 对账记录处理器 — 独立事务
 *
 * ─── 为什么需要独立的 @Transactional(REQUIRES_NEW) ────────────────────────────
 *
 * 对账 Job 处理几百笔 TIMEOUT 交易时，如果所有记录在一个大事务里：
 *   - 某一笔处理失败 → 整批回滚，已修正的状态全部还原 → 下次运行仍需处理同样的记录
 *   - 所有记录持有行锁时间过长 → 影响并发支付写入
 *
 * 修复方案：Job 移除 @Transactional，每笔交易单独在此 Service 中以 REQUIRES_NEW 处理，
 * 一笔失败不影响其他笔，且每笔提交后立即释放行锁。
 *
 * ─── 原子性保证（"校验→写DB" 不被竞争）──────────────────────────────────────
 *
 *   1. REQUIRES_NEW 事务开始 → 通过状态机白名单校验
 *   2. 在同一事务中 transactionMapper.updateById(update) 带 @Version 乐观锁
 *   3. 若另一线程（如银行回调）在步骤 1-2 之间修改了同一行：
 *      → WHERE version=V 返回 0 行 → 抛 OptimisticLockException → 事务回滚 → 无脏数据
 *      → Job 记 failed++，当前 REQUIRES_NEW 事务回滚，其他记录不受影响
 *
 * ─── 两步提交流程（TIMEOUT → RECONCILING → SUCCESS/FAILED）────────────────────
 *
 *   TIMEOUT 不能直接跳到 SUCCESS（状态机白名单禁止），必须经过 RECONCILING 中间态。
 *   这确保了对账修正有审计踪迹（PAYMENT_TRANSACTION.PREVIOUS_STATUS 记录了 RECONCILING 过渡）。
 *
 *   两步都在同一个 REQUIRES_NEW 事务中完成，两步的 DB 更新是原子提交的。
 *   步骤一成功后，MyBatis-Plus 自动递增 version，步骤二使用更新后的 version 继续。
 */
@Service
@Slf4j
public class ReconciliationProcessor {

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private PaymentStateMachine stateMachine;

    /**
     * 处理单笔对账（独立事务，REQUIRES_NEW）
     *
     * ─── 分支路由 ──────────────────────────────────────────────────────────────
     *
     *  PENDING → 直接修正（reconcilePendingDirect）
     *    ∵ 状态机有 PENDING→SUCCESS/FAILED，signatureValid=true 满足 guard。
     *    ∵ PENDING 表示回调延迟/丢失，非超时未知，语义上不需要 RECONCILING 中间态。
     *
     *  Bug fix（原 plan 中的致命 Bug）：
     *    原代码对 PENDING 和 TIMEOUT 统一走 localStatus→RECONCILING 路径，
     *    但状态机只注册了 TIMEOUT→RECONCILING，PENDING→RECONCILING 不在白名单。
     *    结果：所有 PENDING 对账都抛 IllegalStateTransitionException，返回 FAILED，
     *    PENDING 死账永远无法自愈 → 客户付了钱但订单永远 UNPAID！
     *
     *  TIMEOUT → 两步修正（reconcileViaReconcilingPath）
     *    ∵ TIMEOUT→SUCCESS 被状态机禁止（非法转换）
     *    ∵ 必须经过 RECONCILING 留下审计踪迹，记录"系统曾经超时"这一历史事实
     *
     * @param transaction  从批量查询读取的交易（可能存在 stale read）
     * @param gatewayStatus 从网关查询到的最新状态
     * @return 处理结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReconcileResult processOne(PaymentTransaction transaction, GatewayTransactionStatus gatewayStatus) {

        // ── 在新事务中重新读取，获取最新 version 和状态 ──────────────────────────
        // 避免 stale read 导致乐观锁误报（job 批量 select 后，银行回调可能已先修改了某笔记录）
        PaymentTransaction fresh = transactionMapper.selectById(transaction.getId());
        if (fresh == null) {
            log.warn("[对账] 交易不存在（可能已删除），跳过 txn={}", transaction.getTransactionId());
            return ReconcileResult.SKIP;
        }

        // ── 终态保护：已是 SUCCESS/FAILED/REFUNDED 等终态，无需对账修正 ──────────
        if (isTerminalStatus(fresh.getPaymentStatus())) {
            // 可能是：上一笔 REQUIRES_NEW 事务刚刚修正完，或银行回调已先于 job 到达
            log.debug("[对账] 已是终态/退款态，跳过 txn={} status={}", fresh.getTransactionId(), fresh.getPaymentStatus());
            return ReconcileResult.MATCHED;
        }

        String localStatus = fresh.getPaymentStatus();
        String bankStatus = gatewayStatus.getStatus();

        if (localStatus.equals(bankStatus)) {
            updateLastQueryTime(fresh);
            return ReconcileResult.MATCHED;
        }

        // ── 状态不一致，执行对账修正 ──────────────────────────────────────────
        log.warn("[对账] 状态不一致 txn={} 本地={} 银行={}",
                fresh.getTransactionId(), localStatus, bankStatus);

        PaymentStatus localEnum = PaymentStatus.valueOf(localStatus);
        PaymentStatus targetEnum = resolveTargetStatus(bankStatus);

        if (targetEnum == null) {
            log.error("[对账] 无法识别的银行状态: {} txn={}", bankStatus, fresh.getTransactionId());
            return ReconcileResult.FAILED;
        }

        TransitionContext ctx = TransitionContext.builder()
                .transaction(fresh)
                .operator("RECONCILIATION_JOB")
                .remark("对账修正: 本地=" + localStatus + " 银行=" + bankStatus)
                .signatureValid(true)  // 对账场景不涉及回调签名，固定传 true
                .build();

        try {
            if (localEnum == PENDING) {
                // ── PENDING：回调丢失/延迟，直接修正 ──────────────────────────────
                // 状态机白名单：PENDING→SUCCESS（guard: signatureValid=true ✅）
                //              PENDING→FAILED（无 guard ✅）
                return reconcilePendingDirect(fresh, localStatus, bankStatus, targetEnum, ctx);
            } else {
                // ── TIMEOUT：必须经过 RECONCILING 中间态 ──────────────────────────
                // 保留"系统曾经超时"的审计踪迹；TIMEOUT→SUCCESS 被状态机禁止
                return reconcileViaReconcilingPath(fresh, localEnum, localStatus, bankStatus, targetEnum, ctx);
            }

        } catch (IllegalStateTransitionException e) {
            log.error("[对账] 状态机拒绝转换 {} → {}，原因: {} txn={}",
                    localStatus, targetEnum, e.getMessage(), fresh.getTransactionId());
            return ReconcileResult.FAILED;
        }
    }

    // ─── PENDING 直接修正（单步，无 RECONCILING 中间态）───────────────────────────

    private ReconcileResult reconcilePendingDirect(PaymentTransaction fresh,
                                                    String localStatus, String bankStatus,
                                                    PaymentStatus targetEnum,
                                                    TransitionContext ctx) {
        // 状态机白名单校验（不写 DB）
        stateMachine.transition(PENDING, targetEnum, ctx);

        // 校验通过，乐观锁写 DB（一步到位）
        PaymentTransaction upd = buildUpdate(fresh.getId(), fresh.getVersion(),
                localStatus, targetEnum.name(), "RECONCILIATION_JOB",
                "MISMATCH", "对账修正(回调丢失/延迟): PENDING → " + bankStatus);
        upd.setReconciliationTime(new Date());
        int rows = transactionMapper.updateById(upd);
        if (rows == 0) {
            throw new OptimisticLockException(
                    "PENDING直接修正乐观锁冲突 txn=" + fresh.getTransactionId());
        }

        log.warn("[对账] ✅ PENDING直接修正完成 txn={} PENDING → {}",
                fresh.getTransactionId(), targetEnum);

        if (SUCCESS == targetEnum) {
            markOrderNotified(fresh, upd, false);  // orderNotified=0，等待通知 job 处理
            log.error("[对账] 🚨 回调丢失！支付已修正为SUCCESS，等待 OrderSuccessNotificationJob 通知订单系统 txn={}",
                    fresh.getTransactionId());
        }
        return ReconcileResult.MISMATCH;
    }

    // ─── TIMEOUT 两步修正（必须经过 RECONCILING）──────────────────────────────────

    private ReconcileResult reconcileViaReconcilingPath(PaymentTransaction fresh,
                                                         PaymentStatus localEnum,
                                                         String localStatus, String bankStatus,
                                                         PaymentStatus targetEnum,
                                                         TransitionContext ctx) {
        // Step 1: localStatus → RECONCILING（状态机白名单：TIMEOUT→RECONCILING ✅）
        stateMachine.transition(localEnum, RECONCILING, ctx);
        PaymentTransaction upd1 = buildUpdate(fresh.getId(), fresh.getVersion(),
                localStatus, RECONCILING.name(), "RECONCILIATION_JOB", null, null);
        int rows1 = transactionMapper.updateById(upd1);
        if (rows1 == 0) {
            throw new OptimisticLockException(
                    "Step1(→RECONCILING)乐观锁冲突 txn=" + fresh.getTransactionId());
        }
        // MyBatis-Plus @Version：updateById 成功后 upd1.getVersion() 已自动递增
        log.debug("[对账] Step1完成 {} → RECONCILING txn={}", localStatus, fresh.getTransactionId());

        // Step 2: RECONCILING → SUCCESS/FAILED（状态机白名单：RECONCILING→SUCCESS/FAILED ✅）
        // 使用 upd1.getVersion()（step1 递增后的新版本号）作为 step2 乐观锁基准
        ctx.getTransaction().setVersion(upd1.getVersion());
        stateMachine.transition(RECONCILING, targetEnum, ctx);
        PaymentTransaction upd2 = buildUpdate(fresh.getId(), upd1.getVersion(),
                RECONCILING.name(), targetEnum.name(), "RECONCILIATION_JOB",
                "MISMATCH", "对账修正: " + localStatus + " → " + bankStatus);
        upd2.setReconciliationTime(new Date());
        int rows2 = transactionMapper.updateById(upd2);
        if (rows2 == 0) {
            throw new OptimisticLockException(
                    "Step2(→" + targetEnum + ")乐观锁冲突 txn=" + fresh.getTransactionId());
        }

        log.warn("[对账] ✅ 两步修正完成 txn={} {} → RECONCILING → {}",
                fresh.getTransactionId(), localStatus, targetEnum);

        if (SUCCESS == targetEnum) {
            markOrderNotified(fresh, upd2, false); // orderNotified=0，等待通知 job
            log.error("[对账] 🚨 TIMEOUT修正为SUCCESS！等待 OrderSuccessNotificationJob 通知订单系统 txn={}",
                    fresh.getTransactionId());
        }
        return ReconcileResult.MISMATCH;
    }

    /**
     * 将 ORDER_NOTIFIED 字段更新为指定值。
     * 若 notified=false，orderNotified=0 → 等待 OrderSuccessNotificationJob 扫描并通知订单系统。
     * 若 notified=true， orderNotified=1 → 已通知，不再扫描。
     *
     * 注意：此更新不依赖 version（只更新单字段），允许在 step2 之后追加更新。
     */
    private void markOrderNotified(PaymentTransaction fresh, PaymentTransaction lastUpdate, boolean notified) {
        try {
            PaymentTransaction flag = new PaymentTransaction();
            flag.setId(fresh.getId());
            flag.setVersion(lastUpdate.getVersion()); // 使用最后一次写入后的版本号
            flag.setOrderNotified(notified ? 1 : 0);
            transactionMapper.updateById(flag);
        } catch (Exception e) {
            // 非致命错误：ORDER_NOTIFIED 字段更新失败不回滚整个对账事务
            // OrderSuccessNotificationJob 会通过状态兜底扫描成功重新通知
            log.warn("[对账] ORDER_NOTIFIED 字段更新失败（非致命），通知 job 将兜底处理 txn={}",
                    fresh.getTransactionId(), e);
        }
    }

    // ─── 私有辅助方法 ───────────────────────────────────────────────────────────

    /** 构建最小化更新对象（只包含必要字段，避免意外覆盖其他字段） */
    private PaymentTransaction buildUpdate(Long id, Integer version,
                                           String previousStatus, String newStatus,
                                           String updateUser,
                                           String reconciliationStatus, String remarks) {
        PaymentTransaction upd = new PaymentTransaction();
        upd.setId(id);
        upd.setVersion(version);                        // 乐观锁基准版本
        upd.setPreviousStatus(previousStatus);
        upd.setPaymentStatus(newStatus);
        upd.setStatusUpdateTime(new Date());
        upd.setUpdateUser(updateUser);
        if (reconciliationStatus != null) {
            upd.setReconciliationStatus(reconciliationStatus);
        }
        if (remarks != null) {
            upd.setRemarks(remarks);
        }
        return upd;
    }

    /** 仅更新最后查询时间（无状态变更） */
    private void updateLastQueryTime(PaymentTransaction transaction) {
        PaymentTransaction upd = new PaymentTransaction();
        upd.setId(transaction.getId());
        upd.setVersion(transaction.getVersion());
        upd.setReconciliationStatus("MATCHED");
        upd.setReconciliationTime(new Date());
        upd.setLastQueryTime(new Date());   // ← 修复：原代码没有更新 LAST_QUERY_TIME 字段
        transactionMapper.updateById(upd);
    }

    /**
     * 将银行状态字符串映射为 PaymentStatus 枚举
     * 只处理对账场景中银行可能返回的终态
     */
    private PaymentStatus resolveTargetStatus(String bankStatus) {
        if ("SUCCESS".equalsIgnoreCase(bankStatus)) return SUCCESS;
        if ("FAILED".equalsIgnoreCase(bankStatus))  return FAILED;
        return null; // 未知状态，由调用方处理
    }

    private boolean isTerminalStatus(String status) {
        // SUCCESS/FAILED/REFUNDED = 终态，支付结果已确定
        // REFUNDING/PARTIALLY_REFUNDED/REFUND_FAILED = 支付已成功，正在/已做退款处理
        //   → 对账 job 不应重复处理这些已付款的交易（否则会尝试 REFUNDING→RECONCILING，状态机拒绝后返回FAILED，浪费资源）
        return SUCCESS.name().equals(status)
                || FAILED.name().equals(status)
                || REFUNDED.name().equals(status)
                || PARTIALLY_REFUNDED.name().equals(status)
                || REFUNDING.name().equals(status)
                || REFUND_FAILED.name().equals(status);
    }

    /**
     * 对账处理结果枚举
     */
    public enum ReconcileResult {
        /** 本地与银行一致，无需修改 */
        MATCHED,
        /** 状态不一致，已修正 */
        MISMATCH,
        /** 处理失败（网关查询失败、状态机拒绝、乐观锁冲突等） */
        FAILED,
        /** 跳过（记录已不存在） */
        SKIP
    }
}

