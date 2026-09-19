package com.chua.common.support.concurrent.threadflow;

import java.util.concurrent.Callable;

/**
 * 线程执行器接口，定义任务提交与生命周期管理。
 *
 * <p>由 {@link ThreadFlow} 根据 {@link ThreadExecutorType} 选择具体实现，
 * 屏蔽平台线程、虚拟线程、响应式流、同步执行之间的差异。</p>
 *
 * @param <T> 任务返回值类型
 * @author CH
 * @since 2026/08/15
 */
public interface ThreadExecutor<T> {

    /**
     * 提交一个无返回值的任务。
     *
     * @param runnable 待执行任务
     * @return 当前执行器，支持链式调用
     */
    ThreadExecutor<T> addTask(Runnable runnable);

    /**
     * 提交一个有返回值的任务。
     *
     * @param callable 待执行任务
     * @return 当前执行器，支持链式调用
     */
    ThreadExecutor<T> addCallable(Callable<T> callable);

    /**
     * 注册生命周期事件回调。
     *
     * @param listener 事件回调，见 {@link ThreadFlowListener}
     * @return 当前执行器，支持链式调用
     */
    ThreadExecutor<T> listener(ThreadFlowListener listener);

    /**
     * 启动执行并阻塞等待所有任务完成。
     *
     * @return 合并后的执行结果
     * @throws Exception 任务执行异常
     */
    ThreadFlowResult<T> execute() throws Exception;

    /**
     * 关闭执行器，释放底层资源。
     */
    void close();
}
