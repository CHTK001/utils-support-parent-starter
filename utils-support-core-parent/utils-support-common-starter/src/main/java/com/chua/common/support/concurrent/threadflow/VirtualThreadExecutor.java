package com.chua.common.support.concurrent.threadflow;

import com.chua.common.support.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
* 基于虚拟线程的执行器。
*
* <p>使用 JDK 21 虚拟线程，每个任务独立创建虚拟线程执行，轻量且高效。</p>
*
* @author CH
* @since 2026/08/15
 */
public class VirtualThreadExecutor extends AbstractThreadExecutor {

    /**
    * 虚拟线程执行器
     */
    private final ExecutorService executor;

    /**
    * 创建 VirtualThreadExecutor 实例
    * @param strategy strategy
    * @param int int
    * @param long long
    * @param TimeUnit TimeUnit
     */
    public VirtualThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit) {
        this(strategy, threshold, timeout, timeUnit, -1);
    }

    /**
    * 创建 VirtualThreadExecutor 实例
    * @param strategy strategy
    * @param threshold threshold
    * @param timeout timeout
    * @param timeUnit timeUnit
    * @param maxConcurrent maxConcurrent
     */
    public VirtualThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit,
                                 int maxConcurrent) {
        super(strategy, threshold, timeout, timeUnit, maxConcurrent);
        this.executor = ThreadUtils.newVirtualThreadPerTaskExecutor();
    }

    @Override
    /** 提交Tasks */
    protected List<Future<Object>> submitTasks() {
        List<Future<Object>> futures = new ArrayList<>(tasks.size());
        for (var task : tasks) {
            futures.add(executor.submit(task));
        }
        return futures;
    }

    @Override
    /** 关闭 */
    public void close() {
        executor.shutdownNow();
    }
}