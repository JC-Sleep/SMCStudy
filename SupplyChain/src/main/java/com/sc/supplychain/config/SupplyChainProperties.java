package com.sc.supplychain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 供应链业务参数配置（application.yml supply-chain.*）
 */
@Component
@ConfigurationProperties(prefix = "supply-chain")
public class SupplyChainProperties {

    /** 效期预警天数配置 */
    private Expiry expiry = new Expiry();

    /** 对账配置 */
    private Reconcile reconcile = new Reconcile();

    /** 金蝶云配置（Phase2预留） */
    private Kingdee kingdee = new Kingdee();

    public static class Expiry {
        /** NEAR_EXPIRY 预警天数（≤此值触发黄色预警） */
        private int warnDaysNear = 7;
        /** URGENT 预警天数（≤此值触发红色预警） */
        private int warnDaysUrgent = 3;

        public int getWarnDaysNear() { return warnDaysNear; }
        public void setWarnDaysNear(int v) { this.warnDaysNear = v; }
        public int getWarnDaysUrgent() { return warnDaysUrgent; }
        public void setWarnDaysUrgent(int v) { this.warnDaysUrgent = v; }
    }

    public static class Reconcile {
        /** Redis 与 DB 差值超过此阈值则告警 */
        private int diffThreshold = 5;
        public int getDiffThreshold() { return diffThreshold; }
        public void setDiffThreshold(int v) { this.diffThreshold = v; }
    }

    public static class Kingdee {
        private boolean enabled = false;
        private String baseUrl;
        private String appId;
        private String appSecret;
        private int timeout = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getAppId() { return appId; }
        public void setAppId(String v) { this.appId = v; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String v) { this.appSecret = v; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int v) { this.timeout = v; }
    }

    public Expiry getExpiry() { return expiry; }
    public void setExpiry(Expiry e) { this.expiry = e; }
    public Reconcile getReconcile() { return reconcile; }
    public void setReconcile(Reconcile r) { this.reconcile = r; }
    public Kingdee getKingdee() { return kingdee; }
    public void setKingdee(Kingdee k) { this.kingdee = k; }
}

