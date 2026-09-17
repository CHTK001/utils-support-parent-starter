package com.chua.common.support.ai.chat.aggregate.strategy;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
* 混合策略 — 分组路由 + 跨组故障转移。
*
* <p>生产环境最常用的策略。支持多组配置，每组有自己的子策略。
* 组内由子策略 + FailoverTemplate 处理，组间按顺序故障转移。
*
* <p>覆写 {@link #executeSync(List, String, Consumer)} 和
* {@link #executeStream(List, String, Consumer)} 实现自定义分组路由逻辑，
* 不需要 {@link com.chua.common.support.ai.chat.aggregate.AggregateChatClient} 做 {@code instanceof} 判断。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("hybrid")
public class HybridStrategy implements RouterStrategy {

    /** 路由组列表 */
    private final List<GroupRouter> groups;
    /** 健康状态过滤器 */
    private final Predicate<WeightedClient> healthFilter;

    /**
    * 创建 HybridStrategy 实例
    * @param groups groups
    * @param healthFilter Predicate
    * @param healthFilter healthFilter
    */
    public HybridStrategy(List<GroupRouter> groups, Predicate<WeightedClient> healthFilter) {
        this.groups = groups;
        this.healthFilter = healthFilter == null ? wc -> true : healthFilter;
    }

    /**
    * 创建 HybridStrategy 实例
    * @param groups groups
    */
    public HybridStrategy(List<GroupRouter> groups) {
        this(groups, null);
    }

    @Override
    /** 选择 */
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        throw new UnsupportedOperationException(
                "HybridStrategy: use executeSync/executeStream, not select()");
    }

    @Override
    /**
    * 执行Sync
    * @param clients clients
    * @param prompt prompt
    * @param usageCallback usageCallback
    */
    public String executeSync(List<WeightedClient> clients, String prompt,
                              Consumer<AiUsage> usageCallback) throws Exception {
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("No groups configured for hybrid strategy");
        }

        Exception lastError = null;
        for (GroupRouter group : groups) {
            if (!group.matches(prompt)) {
                log.debug("[Hybrid] group={} skipped", group.name());
                continue;
            }
            log.debug("[Hybrid] trying group={}", group.name());
            List<WeightedClient> healthy = filterHealthy(group.clients());
            if (healthy.isEmpty()) {
                log.debug("[Hybrid] group={} skipped: no healthy clients", group.name());
                continue;
            }
            try {
                return group.strategy().executeSync(healthy, prompt, usageCallback);
            } catch (Exception e) {
                lastError = e;
                log.warn("[Hybrid] group={} failed: {}", group.name(), e.getMessage());
            }
        }

        throw new RuntimeException("All groups failed in hybrid strategy", lastError);
    }

    @Override
    /**
    * 执行流式输出
    * @param clients clients
    * @param prompt prompt
    * @param consumer consumer
    */
    public void executeStream(List<WeightedClient> clients, String prompt,
                              Consumer<ChatResponse> consumer) throws Exception {
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("No groups configured for hybrid strategy");
        }

        Exception lastError = null;
        for (GroupRouter group : groups) {
            if (!group.matches(prompt)) {
                continue;
            }
            List<WeightedClient> healthy = filterHealthy(group.clients());
            if (healthy.isEmpty()) {
                continue;
            }
            try {
                group.strategy().executeStream(healthy, prompt, consumer);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("[Hybrid] group={} stream failed: {}", group.name(), e.getMessage());
            }
        }

        throw new RuntimeException("All groups failed in hybrid strategy", lastError);
    }

    /**
    * 过滤掉不健康的客户端
    */
    private List<WeightedClient> filterHealthy(List<WeightedClient> clients) {
        List<WeightedClient> result = new ArrayList<>(clients.size());
        for (WeightedClient wc : clients) {
            if (healthFilter.test(wc)) {
                result.add(wc);
            }
        }
        return result;
    }

    /**
    * 组路由器
    */
    public record GroupRouter(
            String name,
            Predicate<String> condition,
            RouterStrategy strategy,
            List<WeightedClient> clients
    ) {
        /** Matches */
        public boolean matches(String prompt) {
            return condition == null || condition.test(prompt);
        }
    }
}
