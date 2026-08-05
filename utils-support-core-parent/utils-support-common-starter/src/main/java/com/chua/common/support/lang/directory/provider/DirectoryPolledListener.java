package com.chua.common.support.lang.directory.provider;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

/**
 * 目录轮询监听器实现，支持事件回调 + 日志输出。
 * <p>
 * 提供两种使用方式：
 * <ul>
 *   <li>通过 Lambda 或方法引用注册回调，按事件类型分别处理</li>
 *   <li>直接作为监听器，默认输出日志</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * DirectoryPolledListener listener = new DirectoryPolledListener()
 *     .onCreate((event, observer) -> System.out.println("创建: " + observer.getFullPath()))
 *     .onModify((event, observer) -> System.out.println("修改: " + observer.getFullPath()))
 *     .onDelete((event, observer) -> System.out.println("删除: " + observer.getFullPath()));
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Slf4j
public class DirectoryPolledListener implements PolledListener {

    /**
     * 创建事件回调
     */
    @Getter
    private BiConsumer<WatcherEvent, EventObserver> onCreateCallback;

    /**
     * 修改事件回调
     */
    @Getter
    private BiConsumer<WatcherEvent, EventObserver> onModifyCallback;

    /**
     * 删除事件回调
     */
    @Getter
    private BiConsumer<WatcherEvent, EventObserver> onDeleteCallback;

    /**
     * 溢出事件回调
     */
    @Getter
    private BiConsumer<WatcherEvent, EventObserver> onOverflowCallback;

    /**
     * 是否启用日志输出
     */
    private boolean logEnabled = true;

    /**
     * 设置创建事件回调。
     *
     * @param callback 回调函数，参数为 (事件类型, 事件观察者)
     * @return this
     */
    public DirectoryPolledListener onCreate(BiConsumer<WatcherEvent, EventObserver> callback) {
        this.onCreateCallback = callback;
        return this;
    }

    /**
     * 设置修改事件回调。
     *
     * @param callback 回调函数
     * @return this
     */
    public DirectoryPolledListener onModify(BiConsumer<WatcherEvent, EventObserver> callback) {
        this.onModifyCallback = callback;
        return this;
    }

    /**
     * 设置删除事件回调。
     *
     * @param callback 回调函数
     * @return this
     */
    public DirectoryPolledListener onDelete(BiConsumer<WatcherEvent, EventObserver> callback) {
        this.onDeleteCallback = callback;
        return this;
    }

    /**
     * 设置溢出事件回调。
     *
     * @param callback 回调函数
     * @return this
     */
    public DirectoryPolledListener onOverflow(BiConsumer<WatcherEvent, EventObserver> callback) {
        this.onOverflowCallback = callback;
        return this;
    }

    /**
     * 启用或禁用日志输出。
     *
     * @param logEnabled true 启用日志（默认），false 禁用
     * @return this
     */
    public DirectoryPolledListener setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
        return this;
    }

    @Override
    public void onCreate(WatcherEvent event, EventObserver observer) {
        if (logEnabled) {
            log.info("创建: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
        }
        if (onCreateCallback != null) {
            onCreateCallback.accept(event, observer);
        }
    }

    @Override
    public void onModify(WatcherEvent event, EventObserver observer) {
        if (logEnabled) {
            log.info("修改: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
        }
        if (onModifyCallback != null) {
            onModifyCallback.accept(event, observer);
        }
    }

    @Override
    public void onDelete(WatcherEvent event, EventObserver observer) {
        if (logEnabled) {
            log.info("删除: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
        }
        if (onDeleteCallback != null) {
            onDeleteCallback.accept(event, observer);
        }
    }

    @Override
    public void onOverflow(WatcherEvent event, EventObserver observer) {
        if (logEnabled) {
            log.warn("溢出: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
        }
        if (onOverflowCallback != null) {
            onOverflowCallback.accept(event, observer);
        }
    }
}
