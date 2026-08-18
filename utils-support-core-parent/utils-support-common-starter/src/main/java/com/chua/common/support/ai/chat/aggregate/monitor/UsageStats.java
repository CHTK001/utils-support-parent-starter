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
    /** 总调用次数 */
    /** 总数calls */
    private long totalCalls;

    /** 总 Token 数 */
    /** 总数tokens */
    private long totalTokens;

    /** 平均延迟（毫秒） */
    /** AVGlatencyMS */
    private double avgLatencyMs;

    public double avgTokensPerCall() {
        return totalCalls == 0 ? 0 : (double) totalTokens / totalCalls;
    }

    /** 兼容 getAvgTokensPerCall 调用 */
    public double getAvgTokensPerCall() {
        return avgTokensPerCall();
    }
}
