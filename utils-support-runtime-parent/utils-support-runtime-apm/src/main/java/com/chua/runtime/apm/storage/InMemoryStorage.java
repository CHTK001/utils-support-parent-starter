package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.DependencyEdge;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存存储 — 带 LRU + TTL 限制。
 *
 * <p>无数据库场景使用，单进程内有效。重启即清空（除非外加 snapshot 持久化）。</p>
 *
 * <p>容量上限通过 {@code apm.storage.capacity} 配置；TTL 通过
 * {@code apm.storage.retention.ms} 配置。超限时按时间淘汰最旧数据。</p>
 *
 * <p>线程安全：所有 collections 均为并发安全容器，写入无锁；读取时构造新 list。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Log
public class InMemoryStorage implements ApmStorage {

    /** 自增 id 分配器 */
    private final AtomicLong transmissionSeq = new AtomicLong();
    private final AtomicLong leakSeq = new AtomicLong();
    private final AtomicLong logSeq = new AtomicLong();

    /** 传输事件表（id → event） */
    private final Map<Long, TransmissionEvent> transmissions = new ConcurrentHashMap<>();

    /** 依赖图边表（edgeId → edge） */
    private final Map<String, DependencyEdge> dependencies = new ConcurrentHashMap<>();

    /** 泄漏记录表（handleId → record） */
    private final Map<String, LeakRecord> leaks = new ConcurrentHashMap<>();

    /** 日志表（id → record） */
    private final Map<Long, LogRecord> logs = new ConcurrentHashMap<>();

    private volatile int capacity = 100_000;
    private volatile long retentionMillis = 7L * 24 * 60 * 60 * 1000L;

    @Override
    public void start(StorageConfig config) {
        this.capacity = config.getInt("apm.storage.capacity", 100_000);
        this.retentionMillis = config.getLong("apm.storage.retention.ms", 7L * 24 * 60 * 60 * 1000L);
        log.info(String.format("InMemoryStorage 启动: capacity=%d, retentionMs=%d", capacity, retentionMillis));
    }

    @Override
    public void stop() {
        log.info("InMemoryStorage 停止");
    }

    @Override
    public void appendTransmission(TransmissionEvent event) {
        if (event == null) {
            return;
        }
        if (event.getId() == 0L) {
            event.setId(transmissionSeq.incrementAndGet());
        }
        enforceCapacity(transmissions, capacity);
        transmissions.put(event.getId(), event);
    }

    @Override
    public void appendDependency(DependencyEdge edge) {
        if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
            return;
        }
        String edgeId = edge.edgeId();
        // 同一 source→target 边用 record() 累加
        DependencyEdge existing = dependencies.get(edgeId);
        if (existing != null) {
            existing.setCallCount(existing.getCallCount() + edge.getCallCount());
            existing.setTotalDuration(existing.getTotalDuration() + edge.getTotalDuration());
            existing.setErrorCount(existing.getErrorCount() + edge.getErrorCount());
            existing.setLastCallTime(System.currentTimeMillis());
            if (edge.getLastError() != null) {
                existing.setLastError(edge.getLastError());
            }
            return;
        }
        dependencies.put(edgeId, edge);
    }

    @Override
    public void appendLeak(LeakRecord record) {
        if (record == null) {
            return;
        }
        if (record.getId() == 0L) {
            record.setId(leakSeq.incrementAndGet());
        }
        leaks.put(record.getHandleId(), record);
    }

    @Override
    public void appendLog(LogRecord record) {
        if (record == null) {
            return;
        }
        if (record.getId() == 0L) {
            record.setId(logSeq.incrementAndGet());
        }
        enforceCapacity(logs, capacity);
        logs.put(record.getId(), record);
    }

    @Override
    public List<TransmissionEvent> queryTransmissions(Query query) {
        return transmissions.values().stream()
                .filter(e -> matchTime(e.getStartTime(), query))
                .filter(e -> matchString(e.getTraceId(), query.getTraceId()))
                .filter(e -> matchString(e.getSourceHost(), query.getSourceHost()))
                .filter(e -> matchString(e.getTargetHost(), query.getTargetHost()))
                .filter(e -> matchString(e.getProtocol(), query.getProtocol()))
                .filter(e -> matchString(e.getSoftware(), query.getSoftware()))
                .filter(e -> matchString(e.getStatus() != null ? e.getStatus().name() : null, query.getStatus()))
                .filter(e -> !query.isErrorOnly()
                        || (e.getStatus() != null && "ERROR".equals(e.getStatus().name())))
                .sorted(Comparator.comparingLong(TransmissionEvent::getStartTime).reversed())
                .skip(query.getOffset())
                .limit(query.getLimit())
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<DependencyEdge> queryDependencies(Query query) {
        return new ArrayList<>(dependencies.values());
    }

    @Override
    public List<LeakRecord> queryLeaks(Query query) {
        return leaks.values().stream()
                .filter(r -> matchTime(r.getCreatedAt(), query))
                .sorted(Comparator.comparingLong(LeakRecord::getCreatedAt).reversed())
                .skip(query.getOffset())
                .limit(query.getLimit())
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<LogRecord> queryLogs(Query query) {
        return logs.values().stream()
                .filter(r -> matchTime(r.getTimestamp(), query))
                .sorted(Comparator.comparingLong(LogRecord::getTimestamp).reversed())
                .skip(query.getOffset())
                .limit(query.getLimit())
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public Map<String, Long> stats() {
        return Map.of(
                "transmissions", (long) transmissions.size(),
                "dependencies", (long) dependencies.size(),
                "leaks", (long) leaks.size(),
                "logs", (long) logs.size());
    }

    @Override
    public long cleanup(long retentionMillis) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        long removed = 0;
        removed += transmissions.entrySet().stream()
                .filter(e -> e.getValue().getStartTime() < cutoff)
                .peek(e -> transmissions.remove(e.getKey()))
                .count();
        removed += logs.entrySet().stream()
                .filter(e -> e.getValue().getTimestamp() < cutoff)
                .peek(e -> logs.remove(e.getKey()))
                .count();
        removed += leaks.entrySet().stream()
                .filter(e -> e.getValue().getCreatedAt() < cutoff && e.getValue().getClosedAt() > 0)
                .peek(e -> leaks.remove(e.getKey()))
                .count();
        return removed;
    }

    @Override
    public String name() {
        return "inmemory";
    }

    /**
     * 容量超限时按时间淘汰最旧 N% 数据（避免每次都全量排序）。
     */
    private <V> void enforceCapacity(Map<Long, V> map, int cap) {
        if (map.size() < cap) {
            return;
        }
        int evict = Math.max(1, map.size() / 10);
        map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(evict)
                .forEach(e -> map.remove(e.getKey()));
    }

    private static boolean matchTime(long ts, Query q) {
        if (q.getStartTime() != null && ts < q.getStartTime()) {
            return false;
        }
        if (q.getEndTime() != null && ts > q.getEndTime()) {
            return false;
        }
        return true;
    }

    private static boolean matchString(String value, String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.contains(filter);
    }
}