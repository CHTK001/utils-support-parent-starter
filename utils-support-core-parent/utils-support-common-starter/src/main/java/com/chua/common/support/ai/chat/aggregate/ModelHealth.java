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

    /**
    * 创建 ModelHealth 实例
    * @param provider provider
    * @param provider String
    * @param model 模型，不允许为 null
    */
    public ModelHealth(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    /**
     * 获取Provider
     * @return 结果字符串
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取Model
     * @return 结果字符串
     */
    public String getModel() {
        return model;
    }

    /**
     * 是否RateLimited
     * @return 是否成功（true 表示成功）
     */
    public boolean isRateLimited() {
        return rateLimited;
    }

    /**
     * 设置RateLimited
     * @param rateLimited 速率Limited（布尔开关）
     */
    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    /**
     * 是否QuotaExhausted
     * @return 是否成功（true 表示成功）
     */
    public boolean isQuotaExhausted() {
        return quotaExhausted;
    }

    /**
     * 设置QuotaExhausted
     * @param quotaExhausted quotaExhausted（布尔开关）
     */
    public void setQuotaExhausted(boolean quotaExhausted) {
        this.quotaExhausted = quotaExhausted;
    }

    /**
     * 获取ConsecutiveFailures
     * @return 结果数值
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** IncrementConsecutiveFailures */
    public void incrementConsecutiveFailures() {
        this.consecutiveFailures++;
    }

    /** 重置ConsecutiveFailures */
    public void resetConsecutiveFailures() {
        this.consecutiveFailures = 0;
    }

    /**
     * 获取Last校验Time
     * @return 结果数值
     */
    public long getLastCheckTime() {
        return lastCheckTime;
    }

    /**
     * 设置Last校验Time
     * @param lastCheckTime 最后一个校验时间，不允许为 null
     */
    public void setLastCheckTime(long lastCheckTime) {
        this.lastCheckTime = lastCheckTime;
    }

    /**
     * 获取LastFailureReason
     * @return 结果字符串
     */
    public String getLastFailureReason() {
        return lastFailureReason;
    }

    /**
     * 设置LastFailureReason
     * @param lastFailureReason 最后一个FailureReason，不允许为 null
     */
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
     * @return 是否成功（true 表示成功）
     */
    public boolean isHealthy() {
        return !rateLimited && !quotaExhausted;
    }

    @Override
    /** ToString */
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
