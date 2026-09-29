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
     * 组路由器。
     *
     * <p>混合策略的最小配置单元：一个组 = 一组带 {@link #condition} 的候选
     * {@link #clients} + 一个组内子策略 {@link #strategy}。组内由子策略自行做负载均衡与重试，
     * 组间则由 {@link HybridRouterStrategy#executeSync(List, String, Consumer)} 按
     * {@link #groups} 的声明顺序依次故障转移，前一组抛异常才落到下一组。</p>
     *
     * <p>可空性：{@code condition} 与 {@code name} 允许为 null（紧凑构造器未校验，
     * 且 {@code matches} 把 {@code condition == null} 视作「无条件命中」）；
     * {@code clients} 走 {@link List#copyOf}，为 null 即抛空指针，故恒非 null 且元素非 null；
     * {@code strategy} 无显式校验，但调用点紧接着就调用其 {@code executeSync} /
     * {@code executeStream}，因此实际上不允许为 null。</p>
     *
     * @param name      组名，仅用于 {@code [Hybrid]} 日志标识本组，不参与路由判定；
     *                  可为 null，允许与兄弟实现 {@code HybridStrategy.GroupRouter} 一致
     *                  （非分组配置下由配置解析器硬编码为 {@code "default"}，
     *                  分组配置下取组配置的 {@code name}，缺项时为 null）
     * @param condition 组命中条件，入参为本次请求的 prompt 文本；为 null 表示本组无条件命中
     *                  （见 {@link #matches(String)}）。取值来源是组配置的分组条件，
     *                  并可能与 token 分组条件取合取
     * @param strategy  组内子路由策略，由组配置的策略名经 SPI 解析得到
     *                  （未知策略名回退到 {@code failover}）；命中本组后由它执行实际调用，
     *                  抛异常时触发向下一组的故障转移，不允许为 null
     * @param clients   本组的候选客户端（带权重），执行前先经构造器传入的健康过滤器剔除
     *                  不健康项；恒非 null 且元素非 null。取值为空列表的组会在运行期
     *                  以「no healthy clients」被跳过
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
