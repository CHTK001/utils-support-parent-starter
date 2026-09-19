package com.chua.runtime.support.model;

import com.chua.common.support.lang.cmd.LineCallback;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时日志流 — 支持多消费者订阅和环形缓冲区。
 *
 * <p>每个 {@link com.chua.runtime.support.RuntimeInstance} 关联一个 LogStream 实例，
 * 消费者通过 {@link #subscribe(LineCallback)} 注册回调接收实时日志行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LogStream implements LineCallback, AutoCloseable {

    /**
     * 默认环形缓冲区容量
     */
    private static final int DEFAULT_MAX_LINES = 1000;

    /**
     * 环形缓冲区最大行数
     */
    private final int maxLines;

    /**
     * 环形缓冲区
     */
    private final LinkedList<String> buffer;

    /**
     * 订阅者列表
     */
    private final List<LineCallback> subscribers;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 使用默认缓冲区容量创建日志流。
     */
    public LogStream() {
        this(DEFAULT_MAX_LINES);
    }

    /**
     * 创建日志流并指定缓冲区容量。
     *
     * @param maxLines 最大缓存行数
     */
    public LogStream(int maxLines) {
        this.maxLines = maxLines;
        this.buffer = new LinkedList<>();
        this.subscribers = new CopyOnWriteArrayList<>();
        this.closed = false;
    }

    @Override
    /** on线 */
    public synchronized void onLine(String line) {
        if (closed) {
            return;
        }
        buffer.addLast(line);
        if (buffer.size() > maxLines) {
            buffer.removeFirst();
        }
        for (LineCallback subscriber : subscribers) {
            subscriber.onLine(line);
        }
    }

    @Override
    /** on完成 */
    public synchronized void onComplete(int exitCode) {
        if (closed) {
            return;
        }
        for (LineCallback subscriber : subscribers) {
            subscriber.onComplete(exitCode);
        }
    }

    @Override
    /** On记录错误 */
    public synchronized void onError(String command, Throwable throwable) {
        if (closed) {
            return;
        }
        for (LineCallback subscriber : subscribers) {
            subscriber.onError(command, throwable);
        }
    }

    /**
    * 订阅日志行，注册回调接收实时日志。
    *
    * @param callback 日志行回调
     */
    public void subscribe(LineCallback callback) {
        if (!closed) {
            subscribers.add(callback);
        }
    }

    /**
     * 取消订阅。
     *
     * @param callback 已注册的回调
     */
    public void unsubscribe(LineCallback callback) {
        subscribers.remove(callback);
    }

    /**
     * 获取当前缓冲区中的所有日志行。
     *
     * @return 日志行列表（从旧到新）
     */
    public synchronized List<String> getBuffer() {
        return new LinkedList<>(buffer);
    }

    /**
     * 获取最近 N 行日志。
     *
     * @param n 行数
     * @return 最近 N 行日志
     */
    public synchronized List<String> tail(int n) {
        int size = buffer.size();
        if (n >= size) {
            return getBuffer();
        }
        return new LinkedList<>(buffer.subList(size - n, size));
    }

    /**
     * 清空缓冲区。
     */
    public synchronized void clear() {
        buffer.clear();
    }

    /**
     * 关闭日志流，停止接收新日志并通知所有订阅者。
     */
    @Override
    public synchronized void close() {
        this.closed = true;
        subscribers.clear();
        buffer.clear();
    }

    /**
     * 日志流是否已关闭。
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return closed;
    }
}