package com.chua.common.support.taskdistribution.dispatcher;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskDeduplicator;
import com.chua.common.support.taskdistribution.task.TaskPriority;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存派发提供者。
 *
 * <p>基于优先级队列和批量消费，支持取消、暂停、恢复和全局派发控制。
 * 适用于单机模式，零外部依赖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InMemoryDispatcherProvider implements DispatcherProvider {

    /**
     * 优先级队列（高优先级先出队）
     */
    private final PriorityBlockingQueue<QueueEntry> queue;

    /**
     * 监听器
     */
    private DispatcherListener listener;

    /**
     * 消费线程池
     */
    private ThreadPoolExecutor executor;

    /**
     * 是否运行
     */
    private volatile boolean running;

    /**
     * 是否全局暂停
     */
    private volatile boolean globallyPaused;

    /**
     * 暂停的任务集合
     */
    private final Set<String> pausedTasks = ConcurrentHashMap.newKeySet();

    /**
     * 取消的任务集合
     */
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    /**
     * 任务去重器
     */
    private TaskDeduplicator deduplicator;

    /**
     * 队列容量
     */
    private final int capacity;

    /**
     * 批量处理大小
     */
    private volatile int batchSize;

    /**
     * 消费线程数
     */
    private final int consumerThreads;

    /**
     * 默认队列容量
     */
    public static final int DEFAULT_CAPACITY = 1024;

    /**
     * 默认批量大小
     */
    private static final int DEFAULT_BATCH_SIZE = 1;

    /**
     * 默认消费线程数
     */
    private static final int DEFAULT_CONSUMER_THREADS = 1;

    /**
     * 构造内存派发提供者。
     */
    public InMemoryDispatcherProvider() {
        this(DEFAULT_CAPACITY, DEFAULT_BATCH_SIZE, DEFAULT_CONSUMER_THREADS);
    }

    /**
     * 构造内存派发提供者。
     *
     * @param batchSize 每次批量处理的任务数
     */
    public InMemoryDispatcherProvider(int batchSize) {
        this(DEFAULT_CAPACITY, batchSize, DEFAULT_CONSUMER_THREADS);
    }

    /**
     * 构造内存派发提供者。
     *
     * @param capacity         队列容量
     * @param batchSize        批量大小
     * @param consumerThreads  消费线程数
     */
    public InMemoryDispatcherProvider(int capacity, int batchSize, int consumerThreads) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.batchSize = Math.max(1, batchSize);
        this.consumerThreads = Math.max(1, consumerThreads);
        this.queue = new PriorityBlockingQueue<>(this.capacity,
                Comparator.comparingInt(e -> -e.priority().getLevel()));
    }

    /**
     * 启用去重。
     *
     * @param deduplicator 去重器
     */
    public void enableDeduplication(TaskDeduplicator deduplicator) {
        this.deduplicator = deduplicator;
    }

    @Override
    /** 接收 */
    public void receive(Task<?> task) {
        if (task == null || cancelledTasks.contains(task.getTaskId())) {
            return;
        }
        if (deduplicator != null && deduplicator.isDuplicate(task.getTaskId())) {
            return;
        }
        TaskPriority priority = task.getPriority() != null ? task.getPriority() : TaskPriority.MEDIUM;
        queue.offer(new QueueEntry(task, priority));
    }

    @Override
    /** 接收 */
    public void receive(TaskResult<?> result) {
        if (result == null) {
            return;
        }
        queue.offer(new QueueEntry(result, TaskPriority.HIGH));
    }

    @Override
    /** Cancel */
    public boolean cancel(String taskId) {
        if (taskId == null) {
            return false;
        }
        cancelledTasks.add(taskId);
        pausedTasks.remove(taskId);
        return true;
    }

    @Override
    /** 暂停 */
    public boolean pause(String taskId) {
        if (taskId == null) {
            return false;
        }
        pausedTasks.add(taskId);
        return true;
    }

    @Override
    /** 恢复 */
    public boolean resume(String taskId) {
        if (taskId == null) {
            return false;
        }
        pausedTasks.remove(taskId);
        return true;
    }

    @Override
    /** 暂停全部 */
    public void pauseAll() {
        globallyPaused = true;
        log.info("全局派发已暂停");
    }

    @Override
    /** 恢复全部 */
    public void resumeAll() {
        globallyPaused = false;
        log.info("全局派发已恢复");
    }

    @Override
    /** 设置批量获取大小 */
    public void setBatchSize(int batchSize) {
        if (batchSize > 0) {
            this.batchSize = batchSize;
        }
    }

    @Override
    /** Pending计算数量 */
    public int pendingCount() {
        return queue.size();
    }

    @Override
    /** 监听器 */
    public DispatcherProvider listener(DispatcherListener listener) {
        this.listener = listener;
        return this;
    }

    @Override
    /** 开始 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executor = (ThreadPoolExecutor) ThreadUtils.newFixedThreadPool(
                consumerThreads,
                ThreadUtils.newThreadFactory("task-dispatcher-consumer"));
        for (int i = 0; i < consumerThreads; i++) {
            executor.submit(this::consume);
        }
        log.info("内存派发提供者已启动, 队列容量: {}, 批量大小: {}, 消费线程: {}",
                capacity, batchSize, consumerThreads);
    }

    /**
    * 消费循环（支持批量处理）。
    */
    private void consume() {
        while (running) {
            try {
                if (globallyPaused) {
                    ThreadUtils.sleep(100);
                    continue;
                }

                // 批量取出
                int currentBatch = batchSize;
                QueueEntry first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }

                dispatch(first);
                for (int i = 1; i < currentBatch; i++) {
                    QueueEntry entry = queue.poll();
                    if (entry == null) {
                        break;
                    }
                    dispatch(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("派发数据异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 派发单条数据，跳过已取消/暂停的任务。
     * @param entry entry
     */
    private void dispatch(QueueEntry entry) {
        if (listener == null) {
            return;
        }
        Object data = entry.data();
        if (data instanceof Task<?> task) {
            if (cancelledTasks.contains(task.getTaskId())) {
                return;
            }
            if (pausedTasks.contains(task.getTaskId()) || task.isPaused()) {
                return;
            }
            try {
                if (task.getTaskId() != null) {
                    MDC.put(MdcDecorator.KEY_TASK_ID, task.getTaskId());
                }
                if (task.getTraceId() != null) {
                    MDC.put(MdcDecorator.KEY_TRACE_ID, task.getTraceId());
                }
                listener.onTask(task);
            } finally {
                MDC.remove(MdcDecorator.KEY_TASK_ID);
                MDC.remove(MdcDecorator.KEY_TRACE_ID);
            }
        } else if (data instanceof TaskResult<?> result) {
            try {
                if (result.getTaskId() != null) {
                    MDC.put(MdcDecorator.KEY_TASK_ID, result.getTaskId());
                }
                listener.onResult(result);
            } finally {
                MDC.remove(MdcDecorator.KEY_TASK_ID);
            }
        }
    }

    @Override
    /** Await */
    public void await() throws InterruptedException {
        if (executor != null) {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        running = false;
        queue.clear();
        pausedTasks.clear();
        cancelledTasks.clear();
        if (deduplicator != null) {
            deduplicator.close();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("内存派发提供者已关闭");
    }

    /**
     * 优先级队列条目。
     *
     * @param data     任务或结果
     * @param priority 优先级
     * @since 4.0.0.42
     * @return 队列entry的结果
     */
    private record QueueEntry(Object data, TaskPriority priority) {
    }
}
