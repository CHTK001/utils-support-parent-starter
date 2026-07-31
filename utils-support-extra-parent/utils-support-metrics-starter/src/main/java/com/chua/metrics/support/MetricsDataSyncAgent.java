package com.chua.metrics.support;

import com.chua.datasync.agent.support.AbstractDataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MetricsDataSyncAgent extends AbstractDataSyncAgent {

    private final MetricsService metricsService;
    private final long intervalMs;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MetricsDataSyncAgent(String agentId, MetricsService metricsService, long intervalMs) {
        super(agentId);
        this.metricsService = metricsService;
        this.intervalMs = intervalMs > 0 ? intervalMs : 1000L;

        MetricsAgentSource source = new MetricsAgentSource(agentId + ":metrics", metricsService);
        addSource(source);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        super.start();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-push-" + agentId());
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::pushMetrics, 0, intervalMs, TimeUnit.MILLISECONDS);

        log.info("Metrics 推送 Agent 已启动: agentId={}, interval={}ms", agentId(), intervalMs);
    }

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

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public void addSink(DataSyncAgentSink sink) {
        super.addSink(sink);
    }

    /**
     * 内部 Source，将 MetricsService 的快照转为结构化行数据。
     */
    public static class MetricsAgentSource implements DataSyncAgentSource, Directional {

        private final String sourceId;
        private final MetricsService metricsService;
        private volatile boolean closed;

        public MetricsAgentSource(String sourceId, MetricsService metricsService) {
            this.sourceId = sourceId;
            this.metricsService = metricsService;
        }

        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String inputId() {
            return "metrics";
        }

        @Override
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

        private static Map<String, Object> row(long ts, String type, String name, Object... kv) {
            Map<String, Object> r = new HashMap<>();
            r.put("timestamp", ts);
            r.put("metric_type", type);
            if (name != null) r.put("name", name);
            for (int i = 0; i < kv.length; i += 2) {
                r.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return r;
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
