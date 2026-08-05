package com.chua.common.support.ai.chat.aggregate.strategy;

import java.util.List;
import java.util.Random;

/**
 * 权重策略 — 按权重比例随机选择客户端。
 *
 * <p>适用于 API Key 配额不均的场景。使用权重构建概率分布，随机选择。
 * 故障转移由 {@link com.chua.common.support.ai.chat.aggregate.FailoverTemplate} 处理。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WeightedStrategy implements RouterStrategy {

    private final Random random = new Random();

    @Override
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients available");
        }

        int totalWeight = clients.stream().mapToInt(WeightedClient::weight).sum();
        if (totalWeight <= 0) {
            return clients.get(random.nextInt(clients.size()));
        }

        int point = random.nextInt(totalWeight);
        int cumulative = 0;
        for (WeightedClient wc : clients) {
            cumulative += wc.weight();
            if (point < cumulative) {
                return wc;
            }
        }

        return clients.get(clients.size() - 1);
    }
}
