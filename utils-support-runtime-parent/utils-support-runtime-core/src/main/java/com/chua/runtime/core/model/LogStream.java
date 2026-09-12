package com.chua.runtime.core.model;

import com.chua.common.support.lang.cmd.LineCallback;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
* 实时日志流 — 环形缓冲区 + 多消费者订阅。
*
* @author CH
* @since 4.0.0.42
 */
public class LogStream implements LineCallback, AutoCloseable {

    /**
    * 默认缓冲区容量
     */
    private static final int DEFAULT_MAX_LINES = 1000;

    /**
    * 最大行数
     */
    private final int maxLines;

    /**
    * 环形缓冲区
     */
    private final LinkedList<String> buffer;

    /**
    * 订阅者
     */
    private final List<LineCallback> subscribers;

    /**
    * 是否已关闭
     */
    private volatile boolean closed;

    /** 创建 日志流 实例 */
    public LogStream() {
        this(DEFAULT_MAX_LINES);
    }

    /**
    * 创建 日志流 实例
    * @param maxLines 最大线
     */
    public LogStream(int maxLines) {
        this.maxLines = maxLines;
        this.buffer = new LinkedList<>();
        this.subscribers = new CopyOnWriteArrayList<>();
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
        for (LineCallback sub : subscribers) {
            sub.onLine(line);
        }
    }

    @Override
    /** on完成 */
    public synchronized void onComplete(int exitCode) {
        if (closed) {
            return;
        }
        for (LineCallback sub : subscribers) {
            sub.onComplete(exitCode);
        }
    }

    @Override
    /** On记录错误 */
    public synchronized void onError(String command, Throwable throwable) {
        if (closed) {
            return;
        }
        for (LineCallback sub : subscribers) {
            sub.onError(command, throwable);
        }
    }

    /**
    * 订阅
    *
    * @param callback callback
     */
    public void subscribe(LineCallback callback) {
        if (!closed) {
            subscribers.add(callback);
        }
    }

    /**
    * 取消订阅
    *
    * @param callback callback
     */
    public void unsubscribe(LineCallback callback) {
        subscribers.remove(callback);
    }

    /**
    * 获取缓冲
    *
    * @return 获取缓冲的结果
     */
    public synchronized List<String> getBuffer() {
        return new LinkedList<>(buffer);
    }

    /**
    * Tail
    *
    * @param n n
    * @return tail的结果
     */
    public synchronized List<String> tail(int n) {
        int size = buffer.size();
        if (n >= size) {
            return getBuffer();
        }
        return new LinkedList<>(buffer.subList(size - n, size));
    }

    /** Clear */
    public synchronized void clear() {
        buffer.clear();
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        this.closed = true;
        subscribers.clear();
        buffer.clear();
    }

    /**
    * 是否Closed
    *
    * @return 是否关闭的结果
     */
    public boolean isClosed() {
        return closed;
    }
}