package com.chua.common.support.task.retry;

import java.util.concurrent.Callable;
import java.util.function.Predicate;
import org.jspecify.annotations.NullUnmarked;

/**
 * 重试提供者接口
 *
 * <p>定义重试操作的执行规范，是重试机制的顶级抽象接口。
 * 支持自定义重试次数、退避策略、异常过滤和重试监听。
 *
 * <p>核心能力：
 * <ul>
 *   <li>执行带重试能力的任务（Callable / Runnable）</li>
 *   <li>自定义重试配置：最大次数、延迟、退避策略</li>
 *   <li>异常过滤：仅对指定类型的异常进行重试</li>
 *   <li>重试监听：每次重试前的回调通知</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * RetryProvider provider = new JdkRetryProvider();
 * String result = provider.execute(() -> httpClient.get("/api"), config);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public interface RetryProvider {

    /**
     * 执行带重试能力的任务
     *
     * @param <T>    返回值类型
     * @param task   待执行的任务
     * @param config 重试配置
     * @return 任务执行结果
     * @throws Exception 所有重试均失败后抛出最后一次异常
     */
    <T> T execute(Callable<T> task, RetryConfig config) throws Exception;

    /**
     * 执行带重试能力的无返回值任务
     *
     * @param task   待执行的任务
     * @param config 重试配置
     * @throws Exception 所有重试均失败后抛出最后一次异常
     */
    void execute(Runnable task, RetryConfig config) throws Exception;

}
