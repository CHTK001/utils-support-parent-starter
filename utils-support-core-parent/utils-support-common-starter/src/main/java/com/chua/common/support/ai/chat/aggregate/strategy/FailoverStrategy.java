package com.chua.common.support.ai.chat.aggregate.strategy;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 故障转移策略 — 始终选择列表中的第一个客户端。
 *
 * <p>最基础的策略，由 {@link com.chua.common.support.ai.chat.aggregate.FailoverTemplate}
 * 自动处理失败后的重试和切换逻辑，策略本身只负责\"选第一个\"。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public class FailoverStrategy implements RouterStrategy {

    @Override
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients available");
        }
        return clients.get(0);
    }
}
