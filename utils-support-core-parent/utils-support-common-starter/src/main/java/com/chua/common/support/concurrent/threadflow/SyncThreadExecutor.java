package com.chua.common.support.concurrent.threadflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 同步执行器，所有任务在当前线程顺序执行。
 *
 * <p>不涉及线程池，直接调用 {@link Callable#call()}。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
public class SyncThreadExecutor extends AbstractThreadExecutor {

    /**
     * 创建 SyncThreadExecutor 实例
     * @param strategy strategy
     * @param int int
     * @param long long
     * @param TimeUnit TimeUnit
     * @param threshold 方法入参 threshold
     * @param timeout 超时时间，不允许为 null
     * @param timeUnit 时间Unit，不允许为 null
     */
    public SyncThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit) {
        super(strategy, threshold, timeout, timeUnit);
    }

    @Override
    /**
     * 提交Tasks
    */
    protected List<Future<Object>> submitTasks() {
        List<Future<Object>> futures = new ArrayList<>(tasks.size());
        for (var task : tasks) {
            try {
                Object result = task.call();
                futures.add(new CompletedFuture(result));
            } catch (Exception e) {
                futures.add(new FailedFuture(e));
            }
        }
        return futures;
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        // 无需释放资源
    }

    /**
     * 已完成 Future 包装。
     */
    private static final class CompletedFuture implements Future<Object> {
        /**
         * 结果对象
        */
        private final Object result;

        CompletedFuture(Object result) {
            this.result = result;
        }

        @Override
        /**
         * Cancel
        */
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        /**
         * 是否Cancelled
        */
        public boolean isCancelled() {
            return false;
        }

        @Override
        /**
         * 是否Done
        */
        public boolean isDone() {
            return true;
        }

        @Override
        /**
         * 获取
        */
        public Object get() {
            return result;
        }

        @Override
        /**
         * 获取
        */
        public Object get(long timeout, TimeUnit unit) {
            return result;
        }
    }

    /**
     * 失败 Future 包装。
     */
    private static final class FailedFuture implements Future<Object> {
        /**
         * 异常对象
        */
        private final Exception exception;

        FailedFuture(Exception exception) {
            this.exception = exception;
        }

        @Override
        /**
         * Cancel
        */
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        /**
         * 是否Cancelled
        */
        public boolean isCancelled() {
            return false;
        }

        @Override
        /**
         * 是否Done
        */
        public boolean isDone() {
            return true;
        }

        @Override
        /**
         * 获取
        */
        public Object get() throws ExecutionException {
            throw new ExecutionException(exception);
        }

        @Override
        /**
         * 获取
        */
        public Object get(long timeout, TimeUnit unit) throws ExecutionException {
            throw new ExecutionException(exception);
        }
    }
}
