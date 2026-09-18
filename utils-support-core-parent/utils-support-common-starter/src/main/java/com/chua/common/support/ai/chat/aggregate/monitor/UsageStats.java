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
    private long totalCalls;

    /** 总 Token 数 */
    private long totalTokens;

    /** 平均延迟（毫秒） */
    private double avgLatencyMs;

    /**
     * 平均值TokensPer调用
     * @return 结果数值
     */
    public double avgTokensPerCall() {
        return totalCalls == 0 ? 0 : (double) totalTokens / totalCalls;
    }

    /**
     * 兼容 getAvgTokensPerCall 调用
     * @return 结果数值
     */
    public double getAvgTokensPerCall() {
        return avgTokensPerCall();
    }
}
