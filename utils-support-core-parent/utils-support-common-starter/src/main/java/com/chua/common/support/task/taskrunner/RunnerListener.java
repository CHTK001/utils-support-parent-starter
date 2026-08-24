package com.chua.common.support.task.taskrunner;

/**
 * 运行事件监听器 — 以同步回调方式接收 TaskRunner 生命周期事件。
 *
 * <p>监听器实现必须线程安全：事件可能从多个虚拟线程并发发出。
 * 响应式消费可改用 {@code TaskRunner#watch()} 获取 {@code Flux<RunnerEvent>}。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * TaskRunner.of("demo")
 *         .policy(CompletionPolicy.allSuccess())
 *         .listener(event -> log.info("event: {}", event))
 *         .task("a", ctx -> doA())
 *         .execute(input);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface RunnerListener {

    /**
     * 接收运行事件。
     *
     * @param event 运行事件，不为 null
     */
    void onEvent(RunnerEvent event);
}
