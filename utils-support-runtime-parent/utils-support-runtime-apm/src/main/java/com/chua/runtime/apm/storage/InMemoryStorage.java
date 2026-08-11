package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.DependencyEdge;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
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
 * <p>线程安全：所有 collections 均为并发安全容器,容量淘汰使用 synchronized 块保证原子性,
 * 避免迭代与修改的并发问题。</p>
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
        enforceCapacityLong(transmissions);
        transmissions.put(event.getId(), event);
    }

    @Override
    public void appendDependency(DependencyEdge edge) {
        if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
            return;
        }
        String edgeId = edge.edgeId();
        // 同一 source→target 边用 merge() 原子累加 — ConcurrentHashMap.merge 对同 key 的
        // 合并函数在 bin 锁内执行,避免并发 read-modify-write 丢失计数。
        dependencies.merge(edgeId, edge, (existing, newEdge) -> {
            existing.setCallCount(existing.getCallCount() + newEdge.getCallCount());
            existing.setTotalDuration(existing.getTotalDuration() + newEdge.getTotalDuration());
            existing.setErrorCount(existing.getErrorCount() + newEdge.getErrorCount());
            existing.setLastCallTime(System.currentTimeMillis());
            if (newEdge.getLastError() != null) {
                existing.setLastError(newEdge.getLastError());
            }
            return existing;
        });
        // 容量保护(新边插入时才可能超限)
        enforceCapacityStr(dependencies);
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
        enforceCapacityLong(logs);
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
        return cleanupInternal(cutoff);
    }

    /**
     * 线程安全的过期清理 — 使用 iterator.remove() 避免 stream().peek(remove) 的并发修改问题。
     */
    private long cleanupInternal(long cutoff) {
        long removed = 0;
        removed += doRemoveOlder(transmissions, cutoff, (java.util.function.Function<TransmissionEvent, Long>) e -> e.getStartTime());
        removed += doRemoveOlder(logs, cutoff, (java.util.function.Function<LogRecord, Long>) e -> e.getTimestamp());
        // leak 只清理已关闭的过期记录,活跃泄漏永远保留
        removed += removeIfOlderThanLeak(leaks, cutoff, e -> e.getClosedAt() > 0 ? e.getClosedAt() : 0L);
        return removed;
    }

    private static <V> long removeIfOlderThanLeak(Map<String, LeakRecord> map, long cutoff,
                                                  java.util.function.Function<LeakRecord, Long> tsExtractor) {
        return doRemoveOlder(map, cutoff, tsExtractor);
    }

    private static <V> long removeIfOlderThanLong(Map<Long, V> map, long cutoff,
                                                  java.util.function.Function<V, Long> tsExtractor) {
        return doRemoveOlder(map, cutoff, tsExtractor);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> long doRemoveOlder(Map<K, V> map, long cutoff,
                                            java.util.function.Function<? super V, Long> tsExtractor) {
        long count = 0;
        synchronized (map) {
            Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<K, V> e = it.next();
                V v = e.getValue();
                if (v == null) {
                    continue;
                }
                Long ts = tsExtractor.apply(v);
                if (ts != null && ts > 0 && ts < cutoff) {
                    it.remove();
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public String name() {
        return "inmemory";
    }

    /**
     * 容量超限时按主键顺序淘汰最旧 N% 数据。
     * 使用 synchronized 块保证 put/evict 原子性,防止并发越界。
     */
    private <V> void enforceCapacityLong(Map<Long, V> map) {
        synchronized (map) {
            if (map.size() < capacity) {
                return;
            }
            int evict = Math.max(1, map.size() / 10);
            Iterator<Map.Entry<Long, V>> it = map.entrySet().iterator();
            while (it.hasNext() && evict > 0) {
                it.next();
                it.remove();
                evict--;
            }
        }
    }

    /**
     * dependencies Map 的容量淘汰(泛型 key 类型不同,独立方法)
     */
    private void enforceCapacityStr(Map<String, DependencyEdge> map) {
        synchronized (map) {
            if (map.size() < capacity) {
                return;
            }
            int evict = Math.max(1, map.size() / 10);
            Iterator<Map.Entry<String, DependencyEdge>> it = map.entrySet().iterator();
            while (it.hasNext() && evict > 0) {
                it.next();
                it.remove();
                evict--;
            }
        }
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