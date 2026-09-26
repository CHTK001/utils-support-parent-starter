package com.chua.common.support.ai.chat.aggregate.strategy;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.spi.annotations.SpiIgnore;
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
 * {@link #executeStream(List, String, Consumer)} 实现自定义分组路由逻辑。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SpiIgnore
public class HybridRouterStrategy implements RouterStrategy {

    /**
     * 路由组列表
     */
    private final List<GroupRouter> groups;
    /**
     * 健康状态过滤器
     */
    private final Predicate<WeightedClient> healthFilter;

    /**
     * 创建 HybridRouterStrategy 实例
     * @param groups groups
     * @param healthFilter Predicate
     * @param healthFilter healthFilter
     */
    public HybridRouterStrategy(List<GroupRouter> groups, Predicate<WeightedClient> healthFilter) {
        this.groups = groups;
        this.healthFilter = healthFilter == null ? wc -> true : healthFilter;
    }

    /**
     * 创建 HybridRouterStrategy 实例
     * @param groups groups
     */
    public HybridRouterStrategy(List<GroupRouter> groups) {
        this(groups, null);
    }

    /**
     * 选择
     */
    @Override
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        throw new UnsupportedOperationException(
                "HybridRouterStrategy: use executeSync/executeStream, not select()");
    }

    /**
     * 执行Sync
     * @param clients clients
     * @param prompt prompt
     * @param usageCallback usageCallback
     */
    @Override
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

    /**
     * 执行流式输出
     * @param clients clients
     * @param prompt prompt
     * @param consumer consumer
     */
    @Override
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
     * @param clients 方法入参 clients
     * @return 结果列表，无数据时为空列表
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

        /**
         * 规范构造器：对集合组件做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。
         * {@code condition} 允许为 null（表示「无分组条件」），
         * {@code name} 取自配置且可能缺省，故二者均保留 null 语义。</p>
         *
         * @param name      组名称，允许为 null
         * @param condition 组匹配条件，允许为 null
         * @param strategy  子路由策略
         * @param clients   候选客户端列表，不允许为 null
         */
        public GroupRouter {
            clients = List.copyOf(clients);
        }

        /**
         * Matches
         */
        public boolean matches(String prompt) {
            return condition == null || condition.test(prompt);
        }
    }
}
