package com.chua.common.support.ai.chat.aggregate.strategy;

import java.util.Comparator;
import java.util.List;

/**
 * 成本路由策略 — 根据输入复杂度选择最经济的模型。
 *
 * <p>基于 prompt 长度评估复杂度：
 * <ul>
 *   <li>&lt;100 字符 → 低成本模型（列表前部）</li>
 *   <li>100~1000 字符 → 中等成本模型</li>
 *   <li>&gt;1000 字符 → 高成本模型（列表尾部）</li>
 * </ul>
 *
 * <p>客户端应按成本升序排列（weight 越低越便宜）。
 * 故障转移由 {@link com.chua.common.support.ai.chat.aggregate.FailoverTemplate} 处理。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CostStrategy implements RouterStrategy {

    /** 短文本阈值 */
    private static final int SHORT_THRESHOLD = 100;
    /** 中等文本阈值 */
    private static final int MEDIUM_THRESHOLD = 1000;

    @Override
    /** 选择 */
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients available");
        }

        List<WeightedClient> sorted = clients.stream()
                .sorted(Comparator.comparingInt(WeightedClient::weight))
                .toList();

        int complexity = evaluateComplexity(prompt);
        int index = mapComplexityToIndex(complexity, sorted.size());
        return sorted.get(index);
    }

    /** EvaluateComplexity */
    private int evaluateComplexity(String prompt) {
        if (prompt == null || prompt.isEmpty()) return 0;
        int len = prompt.length();
        if (len < SHORT_THRESHOLD) return 0;
        if (len < MEDIUM_THRESHOLD) return 1;
        return 2;
    }

    /** MapComplexityToIndex */
    private int mapComplexityToIndex(int complexity, int total) {
        if (total <= 1) return 0;
        return Math.min(complexity * (total - 1) / 2, total - 1);
    }
}
