package com.chua.remote.support.gateway.core.firewall;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问日志管理器
 *
 * <p>记录所有 HTTP 请求的 IP、路径、时间戳、状态码等信息，并提供
 * 实时 QPS 统计、响应时间统计、状态码分布、Top IP/路径 等监控指标。
 *
 * <p>内部使用线程安全的 {@link ConcurrentLinkedDeque} 存储日志条目，
 * 并维护一个 60 秒的环形缓冲区用于 QPS 计算。
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
public class AccessLogManager {

    /** 日志条目环形缓冲，线程安全双端队列 */
    private final Deque<AccessEntry> logs = new ConcurrentLinkedDeque<>();
    /** 最大日志条目数，超出后从头部淘汰 */
    private final int maxEntries;
    /** 总请求计数器 */
    private final AtomicLong totalRequests = new AtomicLong(0);
    /** 路径命中次数统计（全量） */
    private final Map<String, AtomicLong> pathHitCount = new LinkedHashMap<>();
    /** IP 命中次数统计（全量） */
    private final Map<String, AtomicLong> ipHitCount = new LinkedHashMap<>();

    // ===== QPS 环形缓冲（60 秒） =====
    /** 环形缓冲区大小（60 格，每格代表一秒） */
    private static final int RING_SIZE = 60;
    /** QPS 环形计数数组，索引为 {@code epochSecond % RING_SIZE} */
    private final long[] qpsRing = new long[RING_SIZE];
    /** 当前正在统计的秒级 epoch */
    private volatile long currentSecondEpoch = 0;
    /** 当前秒内的请求计数 */
    private final AtomicLong currentSecondCount = new AtomicLong(0);
    /** 总响应时间累计（毫秒），用于计算平均响应时间 */
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    /** 响应时间采样次数 */
    private final AtomicLong responseTimeSamples = new AtomicLong(0);
    /** 最近 60 秒内的状态码分布 */
    private final Map<String, AtomicLong> recentStatusCodes = new LinkedHashMap<>();
    /** 最近 60 秒内各 IP 的请求次数 */
    private final Map<String, AtomicLong> recentIpHitCount = new LinkedHashMap<>();

    /**
     * 默认构造器，最大日志条目数为 10000
     */
    public AccessLogManager() {
        this(10000);
    }

    /**
     * 指定最大条目数的构造器
     *
     * @param maxEntries 最多保留的日志条目数
     */
    public AccessLogManager(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * 记录一条访问日志
     *
     * <p>同时更新全量 IP/路径统计、QPS 环形缓冲区、最近 60 秒状态码和 IP 分布。
     * 当日志条目数超出 {@link #maxEntries} 时自动淘汰最旧的条目。
     *
     * @param ip             客户端 IP
     * @param path           请求路径
     * @param method         HTTP 方法（GET、POST 等）
     * @param statusCode     响应状态码
     * @param responseTimeMs 响应时间（毫秒）
     * @param userAgent      客户端 User-Agent
     */
    public void log(String ip, String path, String method, int statusCode, long responseTimeMs, String userAgent) {
        totalRequests.incrementAndGet();
        // IP 统计
        ipHitCount.computeIfAbsent(ip, k -> new AtomicLong(0)).incrementAndGet();
        // 路径统计
        String normalizedPath = normalizePath(path);
        pathHitCount.computeIfAbsent(normalizedPath, k -> new AtomicLong(0)).incrementAndGet();

        // 响应时间累计
        if (responseTimeMs >= 0) {
            totalResponseTime.addAndGet(responseTimeMs);
            responseTimeSamples.incrementAndGet();
        }

        // QPS 环形缓冲
        long nowEpoch = System.currentTimeMillis() / 1000;
        advanceRing(nowEpoch);
        currentSecondCount.incrementAndGet();

        // 最近 60 秒统计（状态码 + IP）
        recentStatusCodes.computeIfAbsent(String.valueOf(statusCode), k -> new AtomicLong(0)).incrementAndGet();
        recentIpHitCount.computeIfAbsent(ip, k -> new AtomicLong(0)).incrementAndGet();

        AccessEntry entry = new AccessEntry(ip, path, method, statusCode, responseTimeMs, userAgent, Instant.now());
        logs.addLast(entry);

        // 限制大小
        while (logs.size() > maxEntries) {
            logs.pollFirst();
        }

        if (log.isTraceEnabled()) {
            log.trace("[AccessLog] {} {} {} {}ms", ip, method, path, responseTimeMs);
        }
    }

    /**
     * 推进 QPS 环形缓冲，将过期秒桶归零
     *
     * <p>同步方法，确保线程安全。当时间跨度超过环形缓冲大小时，
     * 将全部桶清零；否则仅归零中间过期的桶。
     *
     * @param nowEpoch 当前秒级时间戳
     */
    private synchronized void advanceRing(long nowEpoch) {
        long last = currentSecondEpoch;
        if (last == 0) {
            currentSecondEpoch = nowEpoch;
            return;
        }
        long gap = nowEpoch - last;
        if (gap <= 0) { return; }
        if (gap >= RING_SIZE) {
            // 全部过期，清零
            Arrays.fill(qpsRing, 0);
            currentSecondEpoch = nowEpoch;
            currentSecondCount.set(0);
            recentStatusCodes.clear();
            recentIpHitCount.clear();
            return;
        }
        // 归零中间过期的桶
        for (long s = last + 1; s <= nowEpoch; s++) {
            int idx = (int) (s % RING_SIZE);
            qpsRing[idx] = 0;
        }
        // 将上一秒的计数写入对应桶
        int lastIdx = (int) (last % RING_SIZE);
        qpsRing[lastIdx] = currentSecondCount.get();
        currentSecondEpoch = nowEpoch;
        currentSecondCount.set(0);
    }

    /**
     * 获取实时 QPS 指标
     *
     * <p>包含当前 QPS、平均 QPS、峰值 QPS、总请求数、平均响应时间、
     * 状态码分布、60 秒 QPS 时序数据以及最近 60 秒 Top 10 IP。
     *
     * @return 指标键值映射
     */
    public Map<String, Object> getRealtimeMetrics() {
        long nowEpoch = System.currentTimeMillis() / 1000;
        advanceRing(nowEpoch);

        // 时序数据（60 个点，从当前秒往前）
        long[] snapshot;
        synchronized (this) {
            snapshot = qpsRing.clone();
        }
        long curEpoch = currentSecondEpoch;
        // 将当前秒计数也加入快照
        long curCount = currentSecondCount.get();

        List<Long> timeSeries = new ArrayList<>();
        for (int i = 0; i < RING_SIZE; i++) {
            int idx = (int) ((curEpoch - RING_SIZE + 1 + i) % RING_SIZE);
            long val = snapshot[idx];
            // 当前秒的值从 currentSecondCount 取
            if (curEpoch - RING_SIZE + 1 + i == curEpoch) { val = curCount; }
            timeSeries.add(val);
        }

        // 当前 QPS = 上一秒的桶值
        int lastIdx = (int) ((curEpoch - 1 + RING_SIZE) % RING_SIZE);
        long currentQps = snapshot[lastIdx];

        // 平均 QPS = 所有非零桶的平均
        long sum = 0;
        int nonZero = 0;
        long peak = 0;
        for (long v : snapshot) {
            if (v > 0) { sum += v; nonZero++; }
            if (v > peak) { peak = v; }
        }
        long avgQps = nonZero > 0 ? sum / nonZero : 0;

        // 平均响应时间
        long samples = responseTimeSamples.get();
        long avgResponseTimeMs = samples > 0 ? totalResponseTime.get() / samples : 0;

        // 状态码分布
        Map<String, Object> statusDist = new LinkedHashMap<>();
        recentStatusCodes.forEach((code, count) -> statusDist.put(code, count.get()));

        // 最近 60 秒 Top 10 IP
        List<Map<String, Object>> topIps = new ArrayList<>();
        recentIpHitCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip", e.getKey());
                    m.put("count", e.getValue().get());
                    topIps.add(m);
                });

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("currentQps", currentQps);
        metrics.put("avgQps", avgQps);
        metrics.put("peakQps", peak);
        metrics.put("totalRequests", totalRequests.get());
        metrics.put("avgResponseTimeMs", avgResponseTimeMs);
        metrics.put("statusCodeDistribution", statusDist);
        metrics.put("qpsTimeSeries", timeSeries);
        metrics.put("topIpsRecent", topIps);
        return metrics;
    }

    /**
     * 获取最近 N 条日志
     *
     * @param count 获取条数
     * @return 最近 count 条日志条目（从新到旧）
     */
    public List<AccessEntry> getRecentLogs(int count) {
        List<AccessEntry> result = new ArrayList<>();
        Iterator<AccessEntry> it = logs.descendingIterator();
        while (it.hasNext() && result.size() < count) {
            result.add(it.next());
        }
        return result;
    }

    /**
     * 按 IP 过滤日志
     *
     * @param ip    目标 IP
     * @param count 最多返回条数
     * @return 匹配的日志条目列表（从新到旧）
     */
    public List<AccessEntry> getLogsByIp(String ip, int count) {
        List<AccessEntry> result = new ArrayList<>();
        Iterator<AccessEntry> it = logs.descendingIterator();
        while (it.hasNext() && result.size() < count) {
            AccessEntry e = it.next();
            if (ip.equals(e.ip)) { result.add(e); }
        }
        return result;
    }

    /**
     * 按路径前缀过滤日志
     *
     * @param path  路径前缀
     * @param count 最多返回条数
     * @return 匹配的日志条目列表（从新到旧）
     */
    public List<AccessEntry> getLogsByPath(String path, int count) {
        List<AccessEntry> result = new ArrayList<>();
        Iterator<AccessEntry> it = logs.descendingIterator();
        while (it.hasNext() && result.size() < count) {
            AccessEntry e = it.next();
            if (e.path != null && e.path.startsWith(path)) { result.add(e); }
        }
        return result;
    }

    /**
     * 获取汇总统计信息
     *
     * <p>包含总请求数、当前日志条目数、Top 10 IP 和 Top 10 路径。
     *
     * @return 统计键值映射
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests", totalRequests.get());
        stats.put("logCount", logs.size());

        // Top 10 IPs
        List<Map<String, Object>> topIps = new ArrayList<>();
        ipHitCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip", e.getKey());
                    m.put("count", e.getValue().get());
                    topIps.add(m);
                });
        stats.put("topIps", topIps);

        // Top 10 paths
        List<Map<String, Object>> topPaths = new ArrayList<>();
        pathHitCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", e.getKey());
                    m.put("count", e.getValue().get());
                    topPaths.add(m);
                });
        stats.put("topPaths", topPaths);

        return stats;
    }

    /**
     * 获取 Top N IP
     *
     * @param limit 返回条数
     * @return IP 及命中次数列表，从高到低排序
     */
    public List<Map<String, Object>> getTopIps(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        ipHitCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(limit)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip", e.getKey());
                    m.put("count", e.getValue().get());
                    result.add(m);
                });
        return result;
    }

    /**
     * 获取 Top N 路径
     *
     * @param limit 返回条数
     * @return 路径及命中次数列表，从高到低排序
     */
    public List<Map<String, Object>> getTopPaths(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        pathHitCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(limit)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", e.getKey());
                    m.put("count", e.getValue().get());
                    result.add(m);
                });
        return result;
    }

    /**
     * 获取指定 IP 的请求次数
     *
     * @param ip 客户端 IP
     * @return 请求次数
     */
    public long getIpRequestCount(String ip) {
        AtomicLong count = ipHitCount.get(ip);
        return count != null ? count.get() : 0;
    }

    /**
     * 清除所有日志和统计数据
     *
     * <p>重置日志队列、路径/IP 统计、QPS 环形缓冲区及响应时间计数。
     */
    public void clear() {
        logs.clear();
        pathHitCount.clear();
        ipHitCount.clear();
        totalRequests.set(0);
        // 重置 QPS 环形缓冲
        synchronized (this) {
            Arrays.fill(qpsRing, 0);
            currentSecondEpoch = 0;
        }
        currentSecondCount.set(0);
        totalResponseTime.set(0);
        responseTimeSamples.set(0);
        recentStatusCodes.clear();
        recentIpHitCount.clear();
        log.info("[AccessLog] 日志已清除");
    }

    /**
     * 标准化路径：去除查询参数部分
     *
     * @param path 原始路径
     * @return 标准化后的路径
     */
    private static String normalizePath(String path) {
        if (path == null) { return "/"; }
        int qIdx = path.indexOf('?');
        return qIdx >= 0 ? path.substring(0, qIdx) : path;
    }

    /**
     * 访问日志条目
     *
     * <p>不可变的日志记录，包含一次 HTTP 请求的完整上下文。
     */
    public static class AccessEntry {
        /** 客户端 IP 地址 */
        public final String ip;
        /** 请求路径 */
        /**
         * 路径
         */
        public final String path;
        /** HTTP 方法 */
        /**
         * 方法名
         */
        public final String method;
        /** HTTP 响应状态码 */
        public final int statusCode;
        /** 响应耗时（毫秒） */
        public final long responseTimeMs;
        /** 客户端 User-Agent */
        public final String userAgent;
        /** 请求时间戳 */
        public final Instant timestamp;

        public AccessEntry(String ip, String path, String method, int statusCode,
                           long responseTimeMs, String userAgent, Instant timestamp) {
            this.ip = ip;
            this.path = path;
            this.method = method;
            this.statusCode = statusCode;
            this.responseTimeMs = responseTimeMs;
            this.userAgent = userAgent;
            this.timestamp = timestamp;
        }
    }
}
