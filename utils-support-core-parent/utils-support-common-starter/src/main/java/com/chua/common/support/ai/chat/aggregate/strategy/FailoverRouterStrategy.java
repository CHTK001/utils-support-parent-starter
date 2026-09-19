package com.chua.common.support.ai.chat.aggregate.strategy;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.util.List;

/**
 * 故障转移策略 — 始终选择列表中的第一个客户端。
 *
 * <p>最基础的策略，由 {@link com.chua.common.support.ai.chat.aggregate.FailoverTemplate}
 * 自动处理失败后的重试和切换逻辑，策略本身只负责"选第一个"。
 * 作为 {@code RouterStrategy} 的默认实现，未知策略名称会回退到本策略。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("failover")
@SpiDefault
public class FailoverRouterStrategy implements RouterStrategy {

    @Override
    /** 选择 */
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients available");
        }
        return clients.getFirst();
    }
}
