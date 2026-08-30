package com.chua.metrics.support;

import com.chua.datasync.agent.support.AbstractDataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统指标推送 Agent，按固定间隔读取 MetricsService 快照并写入已注册的 sink。
 * <p>
 * 内部通过 MetricsAgentSource 将 CPU / 内存 / Swap / 磁盘 / 网络 / Load 各项指标转换为统一行结构。
 * 使用单线程守护线程调度（{@code metrics-push-{agentId}}）。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class MetricsDataSyncAgent extends AbstractDataSyncAgent {

    /**
     * 系统指标服务
     */
    private final MetricsService metricsService;

    /**
     * 推送间隔（毫秒）
     */
    private final long intervalMs;

    /**
     * 调度执行器（单线程守护）
     */
    private ScheduledExecutorService scheduler;

    /**
     * 运行状态标志（CAS 控制 start/stop 幂等）
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param agentId       Agent 标识
     * @param metricsService 系统指标服务
     * @param intervalMs    推送间隔（小于等于 0 视为 1000）
     */
    public MetricsDataSyncAgent(String agentId, MetricsService metricsService, long intervalMs) {
        super(agentId);
        this.metricsService = metricsService;
        this.intervalMs = intervalMs > 0 ? intervalMs : 1000L;

        MetricsAgentSource source = new MetricsAgentSource(agentId + ":metrics", metricsService);
        addSource(source);
    }

    /**
     * 启动 Agent：创建守护线程并按 intervalMs 周期推送。
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        super.start();

        scheduler = ThreadUtils.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-push-" + agentId());
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::pushMetrics, 0, intervalMs, TimeUnit.MILLISECONDS);

        log.info("Metrics 推送 Agent 已启动: agentId={}, interval={}ms", agentId(), intervalMs);
    }

    /**
     * 停止 Agent：关闭调度器，等待 in-flight 任务最多 2 秒。
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        super.stop();
        log.info("Metrics 推送 Agent 已停止: agentId={}", agentId());
    }

    /**
     * 周期任务：将 source 数据写入所有 sink，异常时 warn 而不抛出。
     */
    private void pushMetrics() {
        if (!running.get()) {
            return;
        }

        List<DataSyncAgentSource> sources = sources();
        List<DataSyncAgentSink> sinks = sinks();
        if (sources.isEmpty() || sinks.isEmpty()) {
            return;
        }

        try {
            for (DataSyncAgentSource source : sources) {
                Map<String, Object> params = new HashMap<>();
                Flux<Map<String, Object>> data = source.read(params);

                for (DataSyncAgentSink sink : sinks) {
                    sink.write(data);
                }
            }
        } catch (Exception e) {
            log.warn("Metrics 推送异常: {}", e.getMessage());
        }
    }

    /**
     * @return true 表示 Agent 正在运行
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 注册 sink。
     *
     * @param sink 待注册的 sink
     */
    public void addSink(DataSyncAgentSink sink) {
        super.addSink(sink);
    }

    /**
     * 内部 Source，将 MetricsService 的快照转为结构化行数据。
     */
    public static class MetricsAgentSource implements DataSyncAgentSource, Directional {

        /**
         * source 标识
         */
        private final String sourceId;

        /**
         * 关联的指标服务
         */
        private final MetricsService metricsService;

        /**
         * Source 是否已关闭
         */
        private volatile boolean closed;

        /**
         * @param sourceId       source 标识
         * @param metricsService 系统指标服务
         */
        public MetricsAgentSource(String sourceId, MetricsService metricsService) {
            this.sourceId = sourceId;
            this.metricsService = metricsService;
        }

        @Override
        /** SourceId */
        public String sourceId() {
            return sourceId;
        }

        @Override
        /** InputId */
        public String inputId() {
            return "metrics";
        }

        @Override
        /** 读取 */
        public Flux<Map<String, Object>> read(Map<String, Object> params) {
            return Flux.defer(() -> {
                if (closed) {
                    return Flux.empty();
                }

                MetricsSnapshot snapshot = metricsService.getCurrentSnapshot();
                if (snapshot == null) {
                    return Flux.empty();
                }

                List<Map<String, Object>> rows = buildRows(snapshot);
                return Flux.fromIterable(rows);
            });
        }

        /**
         * 将快照中的 CPU / 内存 / Swap / 磁盘 / 网络 / Load 转换为统一行结构。
         *
         * @param snapshot 指标快照
         * @return 行数据列表
         */
        private List<Map<String, Object>> buildRows(MetricsSnapshot snapshot) {
            List<Map<String, Object>> rows = new ArrayList<>();
            long ts = snapshot.getTimestamp();

            if (snapshot.getCpuCores() != null) {
                for (CpuCore c : snapshot.getCpuCores()) {
                    rows.add(row(ts, "cpu", "cpu-" + c.getId(),
                            "usage", c.getUsage(), "frequency", c.getFrequency()));
                }
            }

            if (snapshot.getMemorySlots() != null) {
                for (MemorySlot m : snapshot.getMemorySlots()) {
                    rows.add(row(ts, "memory", "memory-" + m.getSlot(),
                            "total", m.getTotal(), "used", m.getUsed(), "available", m.getAvailable()));
                }
            }

            rows.add(row(ts, "swap", null,
                    "total", snapshot.getSwapTotal(), "used", snapshot.getSwapUsed()));

            if (snapshot.getDisks() != null) {
                for (DiskInfo d : snapshot.getDisks()) {
                    Map<String, Object> r = row(ts, "disk", d.getName(),
                            "total", d.getTotal(), "used", d.getUsed(), "available", d.getAvailable());
                    r.put("mount_point", d.getMountPoint());
                    r.put("file_system", d.getFileSystem());
                    rows.add(r);
                }
            }

            if (snapshot.getNetworks() != null) {
                for (NetworkInterface n : snapshot.getNetworks()) {
                    rows.add(row(ts, "network", n.getName(),
                            "rx_bytes", n.getReceivedBytes(), "tx_bytes", n.getTransmittedBytes(),
                            "rx_packets", n.getReceivedPackets(), "tx_packets", n.getTransmittedPackets()));
                }
            }

            if (snapshot.getLoad() != null) {
                rows.add(row(ts, "load", null,
                        "load_1m", snapshot.getLoad().getLoad1(),
                        "load_5m", snapshot.getLoad().getLoad5(),
                        "load_15m", snapshot.getLoad().getLoad15()));
            }

            return rows;
        }

        /** Row */
        private static Map<String, Object> row(long ts, String type, String name, Object... kv) {
            Map<String, Object> r = new HashMap<>();
            r.put("timestamp", ts);
            r.put("metric_type", type);
            if (name != null) {
                r.put("name", name);
            }
            for (int i = 0; i < kv.length; i += 2) {
                r.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return r;
        }

        @Override
        /** Direction */
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        /** 关闭 */
        public void close() {
            closed = true;
        }
    }
}
