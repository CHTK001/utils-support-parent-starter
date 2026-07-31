package com.chua.common.support.ai.chat.aggregate.monitor;

import lombok.Builder;
import lombok.Data;

/**
 * 用量统计摘要
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class UsageStats {
    private long totalCalls;
    private long totalTokens;
    private double avgLatencyMs;

    public double avgTokensPerCall() {
        return totalCalls == 0 ? 0 : (double) totalTokens / totalCalls;
    }

    /** 兼容 getAvgTokensPerCall 调用 */
    public double getAvgTokensPerCall() {
        return avgTokensPerCall();
    }
}
