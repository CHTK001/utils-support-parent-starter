package com.chua.common.support.lang.directory;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 可轮询目录的公共支撑件，收敛监听器注册、运行状态与事件分发三件被反复重写的事。
 *
 * <p>此前每个 {@link PolledDirectory} 实现都要自己维护一份 {@code List<PolledListener>}、
 * 一个运行标志，并手写一遍 {@code switch (event) { case CREATE -> ... }} 的分发循环。
 * 复制导致三处行为漂移：</p>
 * <ul>
 *   <li>只有 {@code FileSystemPolledDirectory} 会用
 *       {@link DirectoryPollerEnvironment#hasEvent(WatcherEvent)} 过滤订阅的事件，
 *       轮询型实现无视订阅配置，订阅 {@code DELETE} 的人照样收到 {@code CREATE}；</li>
 *   <li>有的实现在未知事件上 {@code throw new IllegalArgumentException}，
 *       单个监听器的异常会中断同一批剩余监听器的分发；</li>
 *   <li>运行标志与重复 {@code start()} 防护各写各的，行为不一致。</li>
 * </ul>
 * <p>本类把这三点统一：事件先按订阅过滤再分发，分发时逐个监听器隔离异常，
 * 未知事件静默忽略，重复启动由 {@link #markRunning()} 原子拦截。</p>
 *
 * <p>线程安全：监听器列表为 {@link CopyOnWriteArrayList}，可在分发过程中增删；
 * 运行状态为 {@link AtomicBoolean}，由 {@link #markRunning()} / {@link #markStopped()} 维护。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class PolledDirectorySupport {

    /**
     * 被支撑的可轮询目录标识，仅用于日志
     */
    private final String name;

    /**
     * 事件监听器列表
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 运行状态标志
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造支撑件。
     *
     * @param name 被支撑的可轮询目录标识，仅用于日志，建议传实现类名
     */
    public PolledDirectorySupport(String name) {
        this.name = name == null ? "PolledDirectory" : name;
    }

    // ==================== 监听器管理 ====================

    /**
     * 注册事件监听器。
     *
     * @param listener 监听器，为空时忽略
     */
    public void addListener(PolledListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除事件监听器。
     *
     * @param listener 监听器
     * @return 移除前该监听器是否已注册
     */
    public boolean removeListener(PolledListener listener) {
        return listener != null && listeners.remove(listener);
    }

    /**
     * 清空全部监听器。
     */
    public void clearListeners() {
        listeners.clear();
    }

    /**
     * 获取当前已注册的监听器快照。
     *
     * @return 监听器列表副本
     */
    public List<PolledListener> listeners() {
        return List.copyOf(listeners);
    }

    // ==================== 运行状态 ====================

    /**
     * 原子地把状态置为运行中，用于拦截重复启动。
     *
     * @return true 表示本次调用完成了启动，false 表示此前已在运行
     */
    public boolean markRunning() {
        return running.compareAndSet(false, true);
    }

    /**
     * 把状态置为已停止。
     */
    public void markStopped() {
        running.set(false);
    }

    /**
     * 当前是否处于运行中。
     *
     * @return 运行中返回 true
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== 事件分发 ====================

    /**
     * 分发事件，不做订阅过滤。
     *
     * @param currentPath 被监听的目录路径
     * @param event       事件类型
     * @param triggerFile 触发事件的文件名
     */
    public void fire(String currentPath, WatcherEvent event, String triggerFile) {
        fire(currentPath, event, triggerFile, null);
    }

    /**
     * 分发事件，先按订阅配置过滤再回调。
     *
     * <p>{@link WatcherEvent#ALL_KIND} 与空订阅视为订阅全部事件，
     * 语义与 {@link DirectoryPollerEnvironment#hasEvent(WatcherEvent)} 保持一致。</p>
     *
     * @param currentPath 被监听的目录路径
     * @param event       事件类型
     * @param triggerFile 触发事件的文件名
     * @param environment 环境配置，为空时不做过滤
     */
    public void fire(String currentPath, WatcherEvent event, String triggerFile,
                     DirectoryPollerEnvironment environment) {
        if (environment != null && !environment.hasEvent(event)) {
            return;
        }
        EventObserver observer = EventObserver.builder()
                .currentPath(currentPath)
                .triggerFile(triggerFile)
                .eventType(event)
                .build();
        dispatch(observer);
    }

    /**
     * 分发事件并对观察者做额外补充（如文件大小）。
     *
     * @param currentPath 被监听的目录路径
     * @param event       事件类型
     * @param triggerFile 触发事件的文件名
     * @param environment 环境配置，为空时不做过滤
     * @param customizer  观察者补充器，为空时忽略
     */
    public void fire(String currentPath, WatcherEvent event, String triggerFile,
                     DirectoryPollerEnvironment environment, Consumer<EventObserver> customizer) {
        if (environment != null && !environment.hasEvent(event)) {
            return;
        }
        EventObserver observer = EventObserver.builder()
                .currentPath(currentPath)
                .triggerFile(triggerFile)
                .eventType(event)
                .build();
        if (customizer != null) {
            try {
                customizer.accept(observer);
            } catch (RuntimeException e) {
                log.warn("事件观察者补充失败: {}/{}", currentPath, triggerFile, e);
            }
        }
        dispatch(observer);
    }

    /**
     * 分发一个已构造好的观察者。
     *
     * <p>供已经自行构造 {@link EventObserver} 的实现使用（如需要写入
     * {@code OVERFLOW} 特殊语义的 WatchService 场景）。</p>
     *
     * @param observer 事件观察者，为空时忽略
     */
    public void fire(EventObserver observer) {
        if (observer == null) {
            return;
        }
        dispatch(observer);
    }

    /**
     * 向全部监听器通报一次异常。
     *
     * <p>轮询体内的异常如果只写日志，业务方无法感知"我的监听器已经不工作了"，
     * 本方法让异常沿监听器链路可见，由监听器决定是否降级。</p>
     *
     * @param currentPath 被监听的目录路径
     * @param triggerFile 触发事件的文件名，可为空
     * @param error       异常
     */
    public void fireError(String currentPath, String triggerFile, Throwable error) {
        EventObserver observer = EventObserver.builder()
                .currentPath(currentPath)
                .triggerFile(triggerFile)
                .eventType(WatcherEvent.OVERFLOW)
                .build();
        for (PolledListener listener : listeners) {
            try {
                listener.onError(WatcherEvent.OVERFLOW, observer, error);
            } catch (Exception e) {
                log.error("监听器异常回调分发失败: {}", name, e);
            }
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 把事件投递给全部监听器，单个监听器异常不影响其余监听器。
     *
     * @param observer 事件观察者
     */
    private void dispatch(EventObserver observer) {
        WatcherEvent event = observer.getEventType();
        if (event == null || event == WatcherEvent.ALL_KIND) {
            return;
        }
        for (PolledListener listener : listeners) {
            try {
                switch (event) {
                    case CREATE -> listener.onCreate(event, observer);
                    case MODIFY -> listener.onModify(event, observer);
                    case DELETE -> listener.onDelete(event, observer);
                    case OVERFLOW -> listener.onOverflow(event, observer);
                    default -> {
                        // ALL_KIND 已在上方拦截，此处兜底忽略未知事件
                    }
                }
            } catch (Exception e) {
                log.error("监听器分发异常: {} {}", name, event, e);
            }
        }
    }
}
