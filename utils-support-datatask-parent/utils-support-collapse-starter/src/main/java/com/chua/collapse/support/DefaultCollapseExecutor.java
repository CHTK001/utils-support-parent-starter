package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认折叠执行器。
 *
 * <p>核心原理：利用"任务提交-任务执行"之间的时间间隙进行批量收集，无需等待固定时间窗口：</p>
 * <ul>
 *   <li><b>CAS 单收集者</b>：{@link AtomicBoolean} 保证同一时刻仅有一个收集调度在进行，
 *       配合无锁队列实现无锁并发入队与批量出队</li>
 *   <li><b>补收窗口</b>：单次收集未达到 {@link CollapseConfig#getWaitThreshold()} 时，
 *       让出当前收集线程时间片（{@code collectingWaitTime == 0}）或等待指定毫秒
 *       （{@code collectingWaitTime > 0}）后再补收一次，兼顾实时性与批量化</li>
 *   <li><b>入参分组</b>：同一批内按入参 equals 分组，相同入参的多次调用合并执行一次批量逻辑，
 *       并将结果广播回组内全部调用方（适用于幂等/批量查询场景）</li>
 *   <li><b>虚拟线程承载</b>：JDK 21+ 默认收集调度线程与批量执行线程均运行于虚拟线程，
 *       阻塞式批量调用不占用平台线程</li>
 * </ul>
 *
 * @param <INPUT>  单次调用的入参类型
 * @param <OUTPUT> 批量返回类型
 * @author CH
 * @since 2026/09/03
 */
public class DefaultCollapseExecutor<INPUT, OUTPUT> implements CollapseExecutor<INPUT, OUTPUT> {

    /**
     * 折叠配置
     */
    private final CollapseConfig config;

    /**
     * 批量执行函数
     */
    private final CollapseBatchFunction<INPUT, OUTPUT> batchFunction;

    /**
     * 收集调度线程（单线程，负责批量出队与补收）
     */
    private final ExecutorService dispatcher;

    /**
     * 批量执行线程（负责执行分组后的批量逻辑）
     */
    private final ExecutorService batchExecutor;

    /**
     * 待处理任务队列
     */
    private final Queue<Task<INPUT, OUTPUT>> queue = new ConcurrentLinkedQueue<>();

    /**
     * 收集调度状态：保证同一时刻仅有一个收集者在执行
     */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 关闭状态
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造默认折叠执行器。
     *
     * @param config        折叠配置
     * @param batchFunction 批量执行函数
     */
    public DefaultCollapseExecutor(CollapseConfig config, CollapseBatchFunction<INPUT, OUTPUT> batchFunction) {
        this.config = Objects.requireNonNull(config, "config must not be null.");
        this.batchFunction = Objects.requireNonNull(batchFunction, "batchFunction must not be null.");
        boolean virtualThread = config.isVirtualThread() && isVirtualThreadAvailable();
        this.dispatcher = createDispatcher(config.getName(), virtualThread);
        this.batchExecutor = createBatchExecutor(config.getName(), virtualThread);
    }

    @Override
    public OUTPUT execute(INPUT input) throws Throwable {
        checkState();
        Task<INPUT, OUTPUT> task = new Task<>(input, new CompletableFuture<>());
        queue.add(task);
        schedule();
        return await(task);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Task<INPUT, OUTPUT> task;
        while ((task = queue.poll()) != null) {
            task.future().completeExceptionally(new RejectedExecutionException(
                    "CollapseExecutor[" + config.getName() + "] has been closed."));
        }
        dispatcher.shutdown();
        batchExecutor.shutdown();
    }

    /**
     * 状态校验。
     *
     * @throws IllegalStateException 执行器已关闭时抛出
     */
    private void checkState() {
        if (closed.get()) {
            throw new IllegalStateException("CollapseExecutor[" + config.getName() + "] has been closed.");
        }
    }

    /**
     * 触发收集调度：CAS 抢占调度权，抢占成功后将批量收集动作提交至调度线程。
     */
    private void schedule() {
        if (processing.compareAndSet(false, true)) {
            dispatcher.execute(this::dispatch);
        }
    }

    /**
     * 批量收集与分发：单次收集不足阈值时补收一次，随后按入参分组并提交批量执行。
     */
    private void dispatch() {
        try {
            Collection<Task<INPUT, OUTPUT>> tasks = collect();
            if (!tasks.isEmpty()) {
                Collection<Collection<Task<INPUT, OUTPUT>>> groups = grouping(tasks);
                for (Collection<Task<INPUT, OUTPUT>> group : groups) {
                    batchExecutor.execute(() -> runGroup(group));
                }
            }
        } finally {
            processing.set(false);
            if (!closed.get() && !queue.isEmpty()) {
                schedule();
            }
        }
    }

    /**
     * 收集批次：出队全部任务，不足阈值且未关闭时按配置补收一次。
     *
     * @return 本批次任务
     */
    private Collection<Task<INPUT, OUTPUT>> collect() {
        List<Task<INPUT, OUTPUT>> collected = drainOnce();
        if (!closed.get() && collected.size() < config.getWaitThreshold()) {
            long waitTime = config.getCollectingWaitTime();
            if (waitTime == 0) {
                Thread.yield();
            } else if (waitTime > 0) {
                pause(waitTime);
            }
            collected.addAll(drainOnce());
        }
        return collected;
    }

    /**
     * 一次性出队全部任务。
     *
     * @return 出队的任务列表
     */
    private List<Task<INPUT, OUTPUT>> drainOnce() {
        List<Task<INPUT, OUTPUT>> tasks = new ArrayList<>();
        Task<INPUT, OUTPUT> task;
        while ((task = queue.poll()) != null) {
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * 按入参 equals 对批次任务分组，相同入参归入同一组。
     *
     * @param tasks 批次任务
     * @return 分组结果，组间有序
     */
    private Collection<Collection<Task<INPUT, OUTPUT>>> grouping(Collection<Task<INPUT, OUTPUT>> tasks) {
        Map<INPUT, List<Task<INPUT, OUTPUT>>> grouped = new LinkedHashMap<>();
        List<Task<INPUT, OUTPUT>> nullGroup = null;
        for (Task<INPUT, OUTPUT> task : tasks) {
            if (task.input() == null) {
                if (nullGroup == null) {
                    nullGroup = new ArrayList<>(4);
                }
                nullGroup.add(task);
                continue;
            }
            grouped.computeIfAbsent(task.input(), key -> new ArrayList<>(4)).add(task);
        }
        Collection<Collection<Task<INPUT, OUTPUT>>> result = new ArrayList<>(grouped.size() + 1);
        result.addAll(grouped.values());
        if (nullGroup != null) {
            result.add(nullGroup);
        }
        return result;
    }

    /**
     * 执行单个分组：调用一次批量函数，并将结果（或异常）广播给组内全部任务。
     *
     * @param group 同入参的任务组
     */
    private void runGroup(Collection<Task<INPUT, OUTPUT>> group) {
        List<INPUT> inputs = new ArrayList<>(group.size());
        for (Task<INPUT, OUTPUT> task : group) {
            inputs.add(task.input());
        }
        try {
            OUTPUT output = batchFunction.executeBatch(inputs);
            for (Task<INPUT, OUTPUT> task : group) {
                task.future().complete(output);
            }
        } catch (Throwable throwable) {
            for (Task<INPUT, OUTPUT> task : group) {
                task.future().completeExceptionally(throwable);
            }
        }
    }

    /**
     * 阻塞等待本次调用结果。
     *
     * @param task 本次调用任务
     * @return 调用结果
     * @throws Throwable 执行异常（解除 ExecutionException 包装）
     */
    private OUTPUT await(Task<INPUT, OUTPUT> task) throws Throwable {
        try {
            return task.future().get();
        } catch (ExecutionException executionException) {
            throw executionException.getCause();
        }
    }

    /**
     * 等待指定毫秒后补收。
     *
     * @param millis 等待毫秒数
     */
    private static void pause(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断当前运行时是否支持虚拟线程（JDK 21+）。
     *
     * @return 支持返回 true
     */
    private static boolean isVirtualThreadAvailable() {
        try {
            Thread.ofVirtual();
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * 创建收集调度线程池。
     *
     * @param name          执行器名称
     * @param virtualThread 是否使用虚拟线程
     * @return 单线程调度池
     */
    private static ExecutorService createDispatcher(String name, boolean virtualThread) {
        if (virtualThread) {
            ThreadFactory factory = Thread.ofVirtual().name(name + "-collect", 0).factory();
            return Executors.newSingleThreadExecutor(factory);
        }
        return Executors.newSingleThreadExecutor(new PlatformDaemonThreadFactory(name + "-collect"));
    }

    /**
     * 创建批量执行线程池。
     *
     * @param name          执行器名称
     * @param virtualThread 是否使用虚拟线程
     * @return 批量执行线程池
     */
    private static ExecutorService createBatchExecutor(String name, boolean virtualThread) {
        if (virtualThread) {
            ThreadFactory factory = Thread.ofVirtual().name(name + "-batch", 0).factory();
            return Executors.newThreadPerTaskExecutor(factory);
        }
        return Executors.newCachedThreadPool(new PlatformDaemonThreadFactory(name + "-batch"));
    }

    /**
     * 平台守护线程工厂。
     */
    private static final class PlatformDaemonThreadFactory implements ThreadFactory {

        /**
         * 线程名称前缀
         */
        private final String prefix;

        /**
         * 线程序号
         */
        private final AtomicInteger sequence = new AtomicInteger(0);

        private PlatformDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 单次调用任务：入参与结果句柄。
     *
     * @param input  单次调用的入参
     * @param future 结果句柄
     * @param <INPUT>  单次调用的入参类型
     * @param <OUTPUT> 批量返回类型
     */
    private record Task<INPUT, OUTPUT>(INPUT input, CompletableFuture<OUTPUT> future) {
    }
}
