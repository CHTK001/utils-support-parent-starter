package com.chua.common.support.objects.publisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NullUnmarked;

/**
 * 事件发布器。
 *
 * <p>轻量级的事件发布-订阅机制，支持按事件类型注册监听器。
 * 当调用 {@link #publish(Object)} 时，所有匹配事件类型的监听器会被依次调用。</p>
 *
 * <p>内部使用 {@link ConcurrentHashMap} 和 {@link CopyOnWriteArrayList}
 * 保证线程安全，适合高并发场景下的读写操作。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 *   EventPublisher publisher = new EventPublisher();
 *   publisher.register(UserLoginEvent.class, event -> {
 *       System.out.println("用户登录: " + event);
 *   });
 *   publisher.publish(new UserLoginEvent("admin"));
 * }</pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
public class EventPublisher {

    /**
     * 监听器注册表，按事件类型分组。
     * key 为事件类型，value 为该类型对应的监听器列表。
     */
    private final Map<Class<?>, List<EventListener>> listeners = new ConcurrentHashMap<>();

    /**
     * 注册事件监听器。
     *
     * @param <T>      事件类型泛型
     * @param type     事件类型，不可为 null
     * @param listener 事件监听器，不可为 null
     */
    public <T> void register(Class<T> type, EventListener listener) {
        if (type == null || listener == null) {
            return;
        }
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * 注销事件监听器。
     * <p>从事件类型对应的监听器列表中移除首次匹配的实例，未找到则无副作用。</p>
     *
     * @param <T>      事件类型泛型
     * @param type     事件类型
     * @param listener 待注销的监听器实例
     * @return true 表示找到并移除
     */
    public <T> boolean unregister(Class<T> type, EventListener listener) {
        if (type == null || listener == null) {
            return false;
        }
        List<EventListener> list = listeners.get(type);
        if (list == null) {
            return false;
        }
        return list.remove(listener);
    }

    /**
     * 注销事件类型的所有监听器。
     *
     * @param type 事件类型
     * @return true 表示清除了非空列表
     */
    public boolean unregisterAll(Class<?> type) {
        if (type == null) {
            return false;
        }
        List<EventListener> removed = listeners.remove(type);
        return removed != null && !removed.isEmpty();
    }

    /**
     * 发布事件。
     *
     * <p>遍历所有已注册的监听器，将事件分发给匹配的监听器。
     * 事件类型匹配规则：监听器注册的类型是事件类型的父类或相同类型。</p>
     *
     * @param event 事件对象，null 时不处理
     * @return 本次分发调用的监听器数量
     */
    public int publish(Object event) {
        if (event == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<Class<?>, List<EventListener>> entry : listeners.entrySet()) {
            if (entry.getKey().isInstance(event)) {
                for (EventListener listener : entry.getValue()) {
                    try {
                        listener.onEvent(event);
                        count++;
                    } catch (Exception e) {
                        // 单个监听器异常不影响其他监听器
                    }
                }
            }
        }
        return count;
    }

    /**
     * 获取指定事件类型已注册的监听器数量。
     *
     * @param type 事件类型
     * @return 监听器数量
     */
    public int listenerCount(Class<?> type) {
        if (type == null) {
            return 0;
        }
        List<EventListener> list = listeners.get(type);
        return list == null ? 0 : list.size();
    }

    /**
     * 获取所有已注册的事件类型集合。
     *
     * @return 事件类型不可变视图
     */
    public java.util.Set<Class<?>> getEventTypes() {
        return java.util.Collections.unmodifiableSet(listeners.keySet());
    }

    /**
     * 清空所有监听器。
     */
    public void clear() {
        listeners.clear();
    }

    /**
     * 事件监听器函数式接口。
     */
    @FunctionalInterface
    public interface EventListener {
        void onEvent(Object event);
    }
}
