package com.chua.runtime.apm.storage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.chua.runtime.protocol.DependencyEdge;

/**
 * 无操作存储 — 默认 降级。
 *
 * <p>当 SPI 找不到任何实现，或显式配置 {@code apm.storage.type=noop} 时使用。
 * 所有方法都直接 返回，保持原有内存模式兼容。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NoopStorage implements ApmStorage {

    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(NoopStorage.class.getName());

    @Override
    /** 开始 */
    public void start(StorageConfig config) {
        LOG.log(Level.FINE, "NoopStorage 启动 — 所有写入将被丢弃");
    }

    @Override
    /** 停止 */
    public void stop() {
    }

    @Override public void appendTransmission(TransmissionEvent event) {
    }

    @Override public void appendDependency(DependencyEdge edge) {
    }

    @Override public void appendLeak(LeakRecord record) {
    }

    @Override public void appendLog(LogRecord record) {
    }

    @Override public List<TransmissionEvent> queryTransmissions(Query query) {
        return Collections.emptyList();
    }

    @Override public List<DependencyEdge> queryDependencies(Query query) {
        return Collections.emptyList();
    }

    @Override public List<LeakRecord> queryLeaks(Query query) {
        return Collections.emptyList();
    }

    @Override public List<LogRecord> queryLogs(Query query) {
        return Collections.emptyList();
    }

    @Override public Map<String, Long> stats() {
        return Collections.emptyMap();
    }

    @Override public long cleanup(long retentionMillis) {
        return 0L;
    }

    @Override public String name() {
        return DEFAULT_NAME;
    }
}