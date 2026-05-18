package sys.smc.payment.service;

import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import sys.smc.payment.dto.RefundRequest;
import sys.smc.payment.entity.RefundApplication;
import sys.smc.payment.entity.RefundAuditLog;
import sys.smc.payment.enums.RefundApplicationStatus;
import sys.smc.payment.exception.PaymentException;
import sys.smc.payment.gateway.dto.GatewayRefundResponse;
import sys.smc.payment.mapper.RefundApplicationMapper;
import sys.smc.payment.mapper.RefundAuditLogMapper;

import java.util.Date;

/**
 * 退款异步执行服务
 *
 * ⚠️ 必须独立为单独的 @Service 类！
 * 原因：@Async 通过 Spring AOP 代理实现，
 *       如果在同一个类中调用（this.executeAsync），AOP 代理不会介入，
 *       导致 @Async 失效（同步执行，HTTP 线程被阻塞）。
 *       由 RefundApprovalService 注入本类并调用，才能走 Spring 代理。
 *
 * 执行流程：
 *   1. 乐观锁抢占执行权：APPROVED → EXECUTING
 *   2. 调用 PaymentServiceEnhanced.refund()（内含银行 API + 状态机）
 *   3. 根据结果更新 REFUND_APPLICATION → COMPLETED / FAILED
 *   4. 写不可篡改的审计日志
 */
@Service
@Slf4j
public class RefundExecutionService {

    @Autowired
    private RefundApplicationMapper applicationMapper;

    @Autowired
    private RefundAuditLogMapper auditLogMapper;

    @Autowired
    private PaymentServiceEnhanced paymentServiceEnhanced;

    /**
     * 异步执行退款
     * 由 RefundApprovalService.approveApplication() 在审批通过后调用
     *
     * @param applicationId 退款申请ID
     * @param reviewedBy    审批人（异步线程无 ThreadLocal，需显式传入）
     */
    @Async("callbackExecutor")
    public void executeAsync(Long applicationId, String reviewedBy) {

        log.info("[退款执行] 开始异步退款，applicationId={}，operator={}", applicationId, reviewedBy);

        // ── Step 1：从 DB 加载申请单 ──────────────────────────────────────
        RefundApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            log.error("[退款执行] 申请单不存在，applicationId={}", applicationId);
            return;
        }

        // ── Step 2：乐观锁抢占执行权：APPROVED → EXECUTING ──────────────
        // 防止网络重试等导致的并发重复执行
        RefundApplication claimUpdate = new RefundApplication();
        claimUpdate.setId(applicationId);
        claimUpdate.setVersion(app.getVersion());  // 乐观锁
        claimUpdate.setStatus(RefundApplicationStatus.EXECUTING.name());
        claimUpdate.setUpdateUser(reviewedBy);

        int claimed = applicationMapper.updateById(claimUpdate);
        if (claimed == 0) {
            log.warn("[退款执行] 抢占执行权失败（版本冲突，可能已有线程在执行），applicationId={}", applicationId);
            return;
        }
        log.info("[退款执行] 已进入执行状态，applicationId={}", applicationId);

        // ── Step 3：构建退款请求 ───────────────────────────────────────────
        String refundNo = "RF" + IdUtil.getSnowflakeNextIdStr();
        RefundRequest refundReq = RefundRequest.builder()
                .transactionId(app.getTransactionId())
                .refundAmount(app.getRefundAmount())
                .originalAmount(app.getOriginalAmount())
                .refundReason(app.getRefundReason())
                .refundNo(refundNo)
                .operator(reviewedBy)
                .build();

        // ── Step 4：调用银行退款 API（通过 PaymentServiceEnhanced.refund()）──
        // 此方法内含：
        //   a. 验证 PAYMENT_TRANSACTION 状态（SUCCESS / PARTIALLY_REFUNDED）
        //   b. 乐观锁抢占：→ REFUNDING（防并发双重退款）
        //   c. 调用银行 Gateway.refund()
        //   d. 更新 PAYMENT_TRANSACTION：→ PARTIALLY_REFUNDED / REFUNDED + TOTAL_REFUNDED_AMOUNT
        try {
            GatewayRefundResponse response = paymentServiceEnhanced.refund(refundReq);

            if (response.isSuccess()) {
                // ── Step 5a：退款成功 → COMPLETED ─────────────────────────
                RefundApplication successUpdate = new RefundApplication();
                successUpdate.setId(applicationId);
                successUpdate.setStatus(RefundApplicationStatus.COMPLETED.name());
                successUpdate.setRefundNo(refundNo);
                successUpdate.setCompletedAt(new Date());
                successUpdate.setUpdateUser(reviewedBy);
                applicationMapper.updateById(successUpdate);

                insertAuditLog(applicationId, app.getTransactionId(), "EXECUTE_SUCCESS",
                        reviewedBy, null, null,
                        "退款成功，退款单号：" + refundNo);
                log.info("[退款执行] 成功，applicationId={}，refundNo={}", applicationId, refundNo);

            } else {
                // ── Step 5b：网关业务拒绝（如余额不足）→ FAILED ────────────
                markFailed(applicationId, app.getTransactionId(), reviewedBy, response.getErrorMessage());
            }

        } catch (Exception e) {
            // ── Step 5c：网关异常（超时/网络）→ FAILED，人工干预 ────────────
            log.error("[退款执行] 银行网关异常，applicationId={}，需人工核查", applicationId, e);
            markFailed(applicationId, app.getTransactionId(), reviewedBy, e.getMessage());
        }
    }

    // ───────── 内部工具方法 ─────────

    private void markFailed(Long applicationId, String transactionId,
                            String operator, String reason) {
        RefundApplication failUpdate = new RefundApplication();
        failUpdate.setId(applicationId);
        failUpdate.setStatus(RefundApplicationStatus.FAILED.name());
        failUpdate.setFailReason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
        failUpdate.setUpdateUser(operator);
        applicationMapper.updateById(failUpdate);

        insertAuditLog(applicationId, transactionId, "EXECUTE_FAILED",
                operator, null, null,
                "退款失败，原因：" + reason);
        log.error("[退款执行] 失败，applicationId={}，原因={}，请人工处理", applicationId, reason);
    }

    private void insertAuditLog(Long applicationId, String transactionId,
                                String action, String operatorUserId,
                                Integer operatorGroupId, Integer operatorParentGroupId,
                                String remark) {
        try {
            RefundAuditLog auditLog = RefundAuditLog.builder()
                    .id(Long.valueOf(IdUtil.getSnowflakeNextIdStr()))
                    .applicationId(applicationId)
                    .transactionId(transactionId)
                    .action(action)
                    .operatorUserId(operatorUserId)
                    .operatorGroupId(operatorGroupId)
                    .operatorParentGroupId(operatorParentGroupId)
                    .remark(remark)
                    .createTime(new Date())
                    .build();
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计日志写入失败不能影响主流程，但必须告警
            log.error("[审计] 审计日志写入失败，applicationId={}，action={}", applicationId, action, e);
        }
    }
}
