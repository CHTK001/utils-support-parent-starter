package com.chua.common.support.ai.chat.aggregate.strategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮转策略 — 按顺序轮流选择客户端。
 *
 * <p>适用于多 API Key 负载均衡。使用原子计数器保证线程安全。
 * 故障转移由 {@link com.chua.common.support.ai.chat.aggregate.FailoverTemplate} 处理。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RoundRobinRouterStrategy implements RouterStrategy {

    /** 原子计数器 */
    /** 计数器 */
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients available");
        }
        int idx = Math.abs(counter.getAndIncrement()) % clients.size();
        return clients.get(idx);
    }
}
