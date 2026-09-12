package com.chua.ueba.support.engine;

import com.chua.ueba.support.dto.TrafficEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体行为窗口跟踪器。
 * <p>
   * 按实体标识（IP 或 会话 标识）维护最近事件的滑动窗口，为序列模型提供
   * 时序上下文。使用 {@link ConcurrentHashMap} + 同步 方法保证多线程安全，
 * 窗口超长时自动丢弃最旧事件。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class IpBehaviorTracker implements AutoCloseable {

    /**
     * 各实体的窗口队列（实体标识 → 事件队列）
     */
    private final ConcurrentHashMap<String, ArrayDeque<TrafficEvent>> windows;

    /**
     * 窗口最大事件数
     */
    private final int maxWindow;

    /**
     * 构造跟踪器。
     *
     * @param maxWindow 每个实体保留的最大事件数，必须大于 0
     * @throws IllegalArgumentException 当 最大窗口 小于等于 0 时
     */
    public IpBehaviorTracker(int maxWindow) {
        if (maxWindow <= 0) {
            throw new IllegalArgumentException("maxWindow 必须大于 0, 实际: " + maxWindow);
        }
        this.maxWindow = maxWindow;
        this.windows = new ConcurrentHashMap<>(64);
    }

    /**
     * 提取实体的窗口键。
     *
     * @param event 流量事件，不能为 空
     * @return 实体标识；IP 与 会话 标识 均缺失时返回 unknown
     */
    private static String entityKey(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.getIp() != null && !event.getIp().isBlank()) {
            return event.getIp();
        }
        if (event.getSessionId() != null && !event.getSessionId().isBlank()) {
            return event.getSessionId();
        }
        return "unknown";
    }

    /**
     * 向指定实体窗口追加一条事件，超长时丢弃最旧事件。
     *
     * @param event 流量事件，不能为 空
     */
    public synchronized void add(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String key = entityKey(event);
        ArrayDeque<TrafficEvent> queue = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        queue.addLast(event);
        while (queue.size() > maxWindow) {
            queue.pollFirst();
        }
    }

    /**
     * 获取指定实体的窗口事件快照。
     *
     * @param entityId 实体标识，允许为 空（返回空列表）
     * @return 事件不可变快照，无数据时返回空列表，绝不为 空
     */
    public synchronized List<TrafficEvent> snapshot(String entityId) {
        if (entityId == null) {
            return List.of();
        }
        ArrayDeque<TrafficEvent> queue = windows.get(entityId);
        return queue == null ? List.of() : List.copyOf(queue);
    }

    /**
     * 移除指定实体的全部窗口数据。
     *
     * @param entityId 实体标识，允许为 空（忽略）
     */
    public synchronized void remove(String entityId) {
        if (entityId != null) {
            windows.remove(entityId);
        }
    }

    /**
     * 移除所有时间戳早于阈值的过期事件；实体窗口清空后一并移除。
     *
     * @param expireBefore 毫秒时间戳，早于该值的事件将被清除
     */
    public synchronized void expireBefore(long expireBefore) {
        windows.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(event -> event.getTimestamp() < expireBefore);
            return entry.getValue().isEmpty();
        });
    }

    /**
     * 清空全部窗口数据。
     */
    public synchronized void clear() {
        windows.clear();
    }

    /**
     * 当前跟踪的实体数量。
     *
     * @return 实体数
     */
    public int size() {
        return windows.size();
    }

    /**
     * 释放资源，清空窗口数据。
     */
    @Override
    public synchronized void close() {
        windows.clear();
        log.debug("[UEBA-Tracker] 窗口数据已清空");
    }
}
