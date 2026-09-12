package com.chua.common.support.task.retry;

import java.util.concurrent.Callable;

/**
* 重试提供者抽象基类
*
* <p>提供无返回值重试的默认实现，子类只需实现核心重试逻辑：
* {@link #doExecute(Callable, RetryConfig)}。
*
* @author CH
* @since 1.0.0
 */
public abstract class AbstractRetryProvider implements RetryProvider {

    /**
    * 执行无返回值任务：适配为 Callable 后委托给核心重试逻辑。
     */
    @Override
    public void execute(Runnable task, RetryConfig config) throws Exception {
        doExecute(() -> {
            task.run();
            return null;
        }, config);
    }

    /**
    * 执行带返回值任务，直接委托给核心重试逻辑。
    *
    * @param task   待重试任务
    * @param config 重试配置
    * @param <T>    返回值类型
    * @return 任务执行结果
     */
    @Override
    public <T> T execute(Callable<T> task, RetryConfig config) throws Exception {
        return doExecute(task, config);
    }

    /**
    * 核心重试逻辑，由具体实现提供。
    *
    * @param task   待重试任务
    * @param config 重试配置
    * @param <T>    返回值类型
    * @return 任务执行结果
    * @throws Exception 全部重试失败后抛出最后一次异常
     */
    protected abstract <T> T doExecute(Callable<T> task, RetryConfig config) throws Exception;
}
