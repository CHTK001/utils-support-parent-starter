package com.chua.common.support.task.taskrunner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 运行器提供者 SPI — TaskRunner 的执行引擎扩展点。
 *
 * <p>默认实现为 {@link StructuredRunnerProvider}（{@code @Spi("structured")}），
 * 基于 Java 25 结构化并发分层调度 DAG。可通过
 * {@code TaskRunner#provider(String)} 切换其他实现。</p>
 *
 * <p>实现类必须标注 {@code @Spi("实现名")} 注解以纳入 ServiceProvider 扫描。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RunnerProvider {

    /**
     * 同步执行整个拓扑图。
     *
     * <p>实现负责：按层调度、单节点超时/重试/熔断降级包装、策略评估、
     * 事件发布与结果汇总。异步出口由门面在虚拟线程上调用本方法实现。</p>
     *
     * @param graph   已校验的任务拓扑图
     * @param context 运行上下文
     * @param options 执行参数
     * @return 整体运行结果，不为 null
     */
    RunResult run(TaskGraph graph, RunnerContext context, ExecutionOptions options);

    /**
     * 执行参数 — 门面收集的运行配置快照。
     *
     * @param policy         完成策略，必填
     * @param defaultTimeout 全局默认单任务超时，null 表示不限时
     * @param defaultRetry   全局默认重试次数
     * @param listeners      事件监听器列表，可能包含门面的内部桥接监听器
     */
    record ExecutionOptions(CompletionPolicy policy, Duration defaultTimeout,
                            int defaultRetry, List<RunnerListener> listeners) {

        /**
         * 构造校验：policy 必填，listeners 允许为 null（按空列表处理）。
         */
        public ExecutionOptions {
            Objects.requireNonNull(policy, "policy must not be null");
            listeners = listeners == null ? List.of() : List.copyOf(listeners);
        }
    }
}
