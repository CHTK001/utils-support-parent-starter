package com.chua.common.support.ai.chat.aggregate;

import java.time.Instant;

/**
 * 模型健康状态
 *
 * @author CH
 * @since 4.0.0.43
 */
public class ModelHealth {

    /** 服务商 */
    private final String provider;

    /** 模型名称 */
    private final String model;

    /** 是否限流 */
    private boolean rateLimited = false;

    /** 是否余额不足 */
    private boolean quotaExhausted = false;

    /** 连续失败次数 */
    private int consecutiveFailures = 0;

    /** 最后检测时间 */
    private long lastCheckTime = System.currentTimeMillis();

    /** 最后失败原因 */
    private String lastFailureReason;

    public ModelHealth(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    public boolean isQuotaExhausted() {
        return quotaExhausted;
    }

    public void setQuotaExhausted(boolean quotaExhausted) {
        this.quotaExhausted = quotaExhausted;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void incrementConsecutiveFailures() {
        this.consecutiveFailures++;
    }

    public void resetConsecutiveFailures() {
        this.consecutiveFailures = 0;
    }

    public long getLastCheckTime() {
        return lastCheckTime;
    }

    public void setLastCheckTime(long lastCheckTime) {
        this.lastCheckTime = lastCheckTime;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    public void setLastFailureReason(String lastFailureReason) {
        this.lastFailureReason = lastFailureReason;
    }

    /**
     * 重置模型健康状态
     */
    public void reset() {
        this.rateLimited = false;
        this.quotaExhausted = false;
        this.consecutiveFailures = 0;
        this.lastFailureReason = null;
    }

    /**
     * 模型是否健康（可用）
     */
    public boolean isHealthy() {
        return !rateLimited && !quotaExhausted;
    }

    @Override
    public String toString() {
        return "ModelHealth{" +
                "provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", rateLimited=" + rateLimited +
                ", quotaExhausted=" + quotaExhausted +
                ", consecutiveFailures=" + consecutiveFailures +
                ", lastCheckTime=" + lastCheckTime +
                ", lastFailureReason='" + lastFailureReason + '\'' +
                '}';
    }
}
