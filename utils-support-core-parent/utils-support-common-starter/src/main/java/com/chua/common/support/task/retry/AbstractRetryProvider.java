package com.chua.common.support.task.retry;

import java.util.concurrent.Callable;
import org.jspecify.annotations.NullUnmarked;

/**
 * 重试提供者抽象基类
 *
 * <p>提供无返回值重试的默认实现，子类只需实现核心重试逻辑：
 * {@link #doExecute(Callable, RetryConfig)}。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public abstract class AbstractRetryProvider implements RetryProvider {

    @Override
    public void execute(Runnable task, RetryConfig config) throws Exception {
        doExecute(() -> { task.run(); return null; }, config);
    }

    @Override
    public <T> T execute(Callable<T> task, RetryConfig config) throws Exception {
        return doExecute(task, config);
    }

    protected abstract <T> T doExecute(Callable<T> task, RetryConfig config) throws Exception;
}
