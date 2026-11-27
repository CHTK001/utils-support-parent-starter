package com.chua.common.support.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.chua.common.support.utils.CollectionUtils;

/**
 * {@link AgentParallelToolExecutor} 默认实现：固定大小守护线程池。
 *
 * <p>并行度默认取 CPU 核数；任务按索引并发提交，全部完成后按提交顺序收集结果。
 * 任一任务失败时，等待已提交任务结束后抛出 {@link AgentToolExecutionException}
 * （cause 为首个失败异常），不吞异常、不返回残缺结果。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class SimpleAgentParallelToolExecutor implements AgentParallelToolExecutor {

    /**
     * 执行线程池（守护线程，进程退出自动回收）
     */
    private final ExecutorService executor;

    /**
     * 并行度
     */
    private final int parallelism;

    /**
     * 构造执行器。
     *
     * @param parallelism 最大并行度，非正数回退为 CPU 核数
     */
    public SimpleAgentParallelToolExecutor(int parallelism) {
        int size = parallelism > 0 ? parallelism : Runtime.getRuntime().availableProcessors();
        this.parallelism = size;
        this.executor = Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "agent-parallel-tool-" + POOL_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 线程命名序号
     */
    private static final java.util.concurrent.atomic.AtomicLong POOL_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    public <T> List<T> execute(List<Callable<T>> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return List.of();
        }
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(task));
        }
        List<T> results = new ArrayList<>(tasks.size());
        Throwable firstFailure = null;
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentToolExecutionException("并行工具执行被中断", e);
            } catch (ExecutionException e) {
                // 解包 ExecutionException，使 cause 直接指向任务的真实失败异常
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (firstFailure == null) {
                    firstFailure = cause;
                }
                results.add(null);
            } catch (Exception e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                results.add(null);
            }
        }
        if (firstFailure != null) {
            throw new AgentToolExecutionException("并行工具执行存在失败任务", firstFailure);
        }
        return results;
    }

    /**
     * 并行度。
     *
     * @return 最大并行度
     */
    public int parallelism() {
        return parallelism;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
