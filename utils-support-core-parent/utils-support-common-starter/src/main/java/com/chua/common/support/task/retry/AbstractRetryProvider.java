package com.chua.common.support.task.retry;

import java.util.concurrent.Callable;

/**
 * 重试提供者抽象基类
 *
 * <p>提供无返回值重试的默认实现，子类只需实现核心重试逻辑：
 * {@link #doExecute(Callable, RetryConfig)}。
 *
 * @since 1.0.0
 */
public abstract class AbstractRetryProvider implements RetryProvider {

    @Override
    /** 执行 */
    public void execute(Runnable task, RetryConfig config) throws Exception {
        doExecute(() -> { task.run(); return null; }, config);
    }

    @Override
    /** 执行 */
    public <T> T execute(Callable<T> task, RetryConfig config) throws Exception {
        return doExecute(task, config);
    }

    /** Do执行 */
    protected abstract <T> T doExecute(Callable<T> task, RetryConfig config) throws Exception;
}
