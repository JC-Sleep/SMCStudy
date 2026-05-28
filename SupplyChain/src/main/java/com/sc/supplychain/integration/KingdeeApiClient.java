package com.sc.supplychain.integration;

import com.sc.supplychain.config.SupplyChainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 金蝶云 API HTTP Client（Phase2预留外壳）
 *
 * TODO Phase2: 实现以下方法
 *   - pushSalesVoucher()   → POST /api/voucher/sales
 *   - pushCostVoucher()    → POST /api/voucher/cost
 *   - pushApVoucher()      → POST /api/voucher/ap（应付）
 *
 * 凭证映射：
 *   旺生活出库单  → 销售凭证（借：应收账款  贷：主营业务收入）
 *   供应商采购入库 → 应付凭证（借：库存商品  贷：应付账款）
 *   出库成本     → 成本凭证（借：主营业务成本  贷：库存商品）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KingdeeApiClient {

    private final SupplyChainProperties properties;

    private OkHttpClient buildClient() {
        SupplyChainProperties.Kingdee config = properties.getKingdee();
        return new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 推送销售凭证（出库单 → 应收+收入）
     * TODO Phase2
     */
    public void pushSalesVoucher(String orderNo, Object voucherData) {
        if (!properties.getKingdee().isEnabled()) {
            log.debug("[金蝶] 未启用，跳过销售凭证推送 orderNo={}", orderNo);
            return;
        }
        // TODO Phase2: build request, call API, handle response, write KingdeeSync record
        log.info("[金蝶] TODO Phase2 - pushSalesVoucher orderNo={}", orderNo);
    }

    /**
     * 推送应付凭证（采购入库 → 库存商品+应付账款）
     * TODO Phase2
     */
    public void pushApVoucher(String purchaseNo, Object voucherData) {
        if (!properties.getKingdee().isEnabled()) return;
        // TODO Phase2
        log.info("[金蝶] TODO Phase2 - pushApVoucher purchaseNo={}", purchaseNo);
    }

    /**
     * 推送成本凭证（出库成本 → 主营成本+库存商品）
     * TODO Phase2
     */
    public void pushCostVoucher(String orderNo, Object voucherData) {
        if (!properties.getKingdee().isEnabled()) return;
        // TODO Phase2
        log.info("[金蝶] TODO Phase2 - pushCostVoucher orderNo={}", orderNo);
    }
}
