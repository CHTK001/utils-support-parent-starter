package com.chua.common.support.ai.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内评测记录器。
 *
 * <p>实现 {@link AgentEvalHook}，把每次运行结果收进有界环形缓冲
 * （默认 1024 条，超限淘汰最旧），供评测断言、回归对比与回放检查读取。
 * 仅适用于进程内观测；需要持久化时请自行实现 {@link AgentEvalHook} 落库。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class InMemoryAgentEvalRecorder implements AgentEvalHook {

    /**
     * 默认容量
     */
    public static final int DEFAULT_CAPACITY = 1024;

    /**
     * 记录缓冲（FIFO，超限淘汰最旧）
     */
    private final Deque<AgentEvalRecord> records = new ArrayDeque<>();

    /**
     * 容量上限
     */
    private final int capacity;

    /**
     * 缓冲锁（ArrayDeque 非线程安全）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 构造记录器（默认容量）。
     */
    public InMemoryAgentEvalRecorder() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 构造记录器。
     *
     * @param capacity 容量上限（&lt;=0 时取默认值）
     */
    public InMemoryAgentEvalRecorder(int capacity) {
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
    }

    @Override
    public void onRunStart(AgentEvalContext context) {
        // 开始事件无持久信息，记录器只在结束时落记录
    }

    @Override
    public void onRunEnd(AgentEvalRecord record) {
        if (record == null) {
            return;
        }
        lock.lock();
        try {
            records.addLast(record);
            while (records.size() > capacity) {
                records.pollFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取全部记录（按发生顺序）。
     *
     * @return 记录快照
     */
    public List<AgentEvalRecord> records() {
        lock.lock();
        try {
            return new ArrayList<>(records);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当前记录数。
     *
     * @return 记录数
     */
    public int size() {
        lock.lock();
        try {
            return records.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空记录。
     */
    public void clear() {
        lock.lock();
        try {
            records.clear();
        } finally {
            lock.unlock();
        }
    }
}
