package com.chua.rocksdb.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
/**
 * 基于 RocksDB（嵌入式本地目录）的时序存储实现（{@link SeriesEngine} SPI，别名 {@code rocksdb}）。
 *
 * <p>全本地、无外部服务：RocksDB 为 LSM-Tree 键值库，本引擎把每个时序点写成一条键值记录，
 * 键布局经过设计，使「同一 {@code (target, monitorId, metricName)} 的时间点」在字节序上
 * 天然按时序排列，从而支持前缀扫描 + 时间范围裁剪：</p>
 *
 * <pre>
 * 点：  SER:1:&lt;target&gt;:&lt;monitorId&gt;:&lt;metricName&gt;:&lt;16 位时序化时间戳&gt;  → 8 字节 double
 * 索引：SER:1:IDX:&lt;target&gt;:&lt;monitorId&gt;:&lt;metricName&gt;                 → "1"
 * </pre>
 *
 * <p><b>时间戳编码</b>：{@code ts ^ Long.MIN_VALUE} 后格式化为定长 16 位十六进制，
 * 使有符号 long 的字典序与数值序一致（含负时间戳），前缀扫描即为时间升序。</p>
 *
 * <p><b>聚合语义</b>：曲线在 Java 侧按统一窗口均值降采样（{@link SeriesEngine#downsample}），
 * 与 DataSource / Redis / InfluxDB / QuestDB 实现保持一致。指标名通过索引键发现，
 * 与 Redis 实现同构；保留期清理按点键直接删除，并回收已清空的指标索引。</p>
 *
 * <h2>SPI 配置</h2>
 * <pre>{@code
 * Properties props = new Properties();
 * props.setProperty("path", "data/series-rocksdb");   // 必填，RocksDB 数据目录（不存在则创建）
 * props.setProperty("datasource", "default");          // 可选，数据源名，缺省 default
 * SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("rocksdb", props);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("rocksdb")
public class RocksDbSeriesEngine implements SeriesEngine, AutoCloseable {

    /**
     * 引擎标识（与配置 {@code monitor.timeseries.engine} 对应）
     */
    private static final String ENGINE_NAME = "rocksdb";

    /**
     * 时序点键前缀（与 RocksDB 引擎其它键空间隔离）
     */
    private static final String KEY_PREFIX = "SER:1:";

    /**
     * 指标索引键前缀
     */
    private static final String INDEX_PREFIX = "SER:1:IDX:";

    /**
     * 默认数据源名
     */
    private static final String DEFAULT_DATASOURCE = "default";

    /**
     * 索引键值（仅表示存在）
     */
    private static final byte[] INDEX_VALUE = {'1'};

    /**
     * 批量写入的操作类型：put
     */
    private static final byte[] OP_PUT = {0};

    /**
     * 缺省单次曲线点数上限
     */
    private static final int MAX_SERIES_POINTS = 1440;

    /**
     * RocksDB 引擎（本引擎自建并负责关闭），未配置时为 null
     */
    private final RocksDbEngine engine;

    /**
     * 数据源名
     */
    private final String datasource;

    /**
     * 是否可用
     */
    private final boolean configured;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 通过 SPI 配置构造引擎。
     *
     * @param properties 配置：path（或 url）必填、datasource 可选
     */
    public RocksDbSeriesEngine(Properties properties) {
        this(path(properties), properties == null ? DEFAULT_DATASOURCE
                : properties.getProperty("datasource", DEFAULT_DATASOURCE));
    }

    /**
     * 通过数据目录与数据源名构造引擎。
     *
     * @param path       数据目录，为空则引擎不可用
     * @param datasource 数据源名
     */
    public RocksDbSeriesEngine(String path, String datasource) {
        this.datasource = datasource == null || datasource.isBlank() ? DEFAULT_DATASOURCE : datasource;
        if (path == null || path.isBlank()) {
            this.engine = null;
            this.configured = false;
            log.warn("[SeriesEngine] RocksDbSeriesEngine 不可用：path 未配置");
            return;
        }
        RocksDbEngine opened = null;
        try {
            opened = new RocksDbEngine();
            opened.addDataSource(this.datasource, path);
        } catch (RuntimeException e) {
            log.warn("[SeriesEngine] RocksDbSeriesEngine 打开失败 path={}: {}", path, e.getMessage());
            opened = null;
        }
        this.engine = opened;
        this.configured = opened != null;
    }

    @Override
    public String engine() {
        return ENGINE_NAME;
    }

    @Override
    public boolean isAvailable() {
        return configured && !closed;
    }

    @Override
    public void writePoint(String target, Long monitorId, String metricName, double value, long timestamp) {
        if (target == null || monitorId == null || metricName == null) {
            throw new IllegalArgumentException("时序写入缺少必填参数: target/monitorId/metricName");
        }
        if (!SqlName.isWord(target) || !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法标识符 target=" + target + " metric=" + metricName);
        }
        if (!isAvailable()) {
            throw new IllegalStateException("RocksDB 引擎不可用（未配置或已关闭），拒绝写入 target="
                    + target + " metric=" + metricName);
        }
        // 点与指标索引同批原子写入，避免崩溃后出现「有索引无点」或「有点无索引」的悬空状态
        List<byte[][]> ops = Wraps.list(2);
        ops.add(new byte[][]{OP_PUT, bytes(pointKey(target, monitorId, metricName, timestamp)), encodeDouble(value)});
        ops.add(new byte[][]{OP_PUT, bytes(indexKey(target, monitorId, metricName)), INDEX_VALUE});
        try {
            engine.writeBatch(datasource, ops);
        } catch (RuntimeException e) {
            log.error("[SeriesEngine] RocksDB 写入失败 target={} metric={}", target, metricName, e);
            throw new IllegalStateException("RocksDB 时序写入失败 target=" + target
                    + " metric=" + metricName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> metrics(String target, Long monitorId) {
        if (target == null) {
            throw new IllegalArgumentException("时序指标发现缺少必填参数: target");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (!isAvailable()) {
            return List.of();
        }
        String prefix = monitorId == null ? INDEX_PREFIX + target + ":"
                : INDEX_PREFIX + target + ":" + monitorId + ":";
        Set<String> found = Wraps.sortedSet();
        try {
            for (Map.Entry<byte[], byte[]> entry : engine.scanBytes(datasource, bytes(prefix))) {
                // 索引键：SER:1:IDX:<target>:<monitorId>:<metric>
                String[] parts = body(toString(entry.getKey()), INDEX_PREFIX).split(":");
                if (parts.length == 3) {
                    found.add(parts[2]);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[SeriesEngine] RocksDB 指标发现失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            throw new IllegalStateException("RocksDB 时序指标发现失败 target=" + target + ": " + e.getMessage(), e);
        }
        return Wraps.list(found);
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, String metricName, int hours, String window) {
        if (target == null || monitorId == null) {
            throw new IllegalArgumentException("时序查询缺少必填参数: target/monitorId");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (metricName != null && !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法 metricName: " + metricName);
        }
        SeriesEngine.requirePositiveHours(hours);
        if (!isAvailable()) {
            return List.of();
        }
        // 一条曲线只能对应一个指标，未显式给出时按唯一性规则推断，多个指标必须报错
        String metric = metricName != null ? metricName : resolveSingleMetric(target, monitorId);
        if (metric == null) {
            return List.of();
        }
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) hours * 3600_000L;
        String prefix = pointPrefix(target, monitorId, metric);
        List<Object[]> points = Wraps.list();
        int dropped = 0;
        try {
            for (Map.Entry<byte[], byte[]> entry : engine.scanBytes(datasource, bytes(prefix))) {
                String[] parts = body(toString(entry.getKey()), KEY_PREFIX).split(":");
                if (parts.length != 4) {
                    dropped++;
                    continue;
                }
                long ts = decodeTs(parts[3]);
                if (ts < startMs || ts > nowMs) {
                    continue;
                }
                byte[] raw = entry.getValue();
                if (raw != null && raw.length == Long.BYTES) {
                    points.add(new Object[]{ts, decodeDouble(raw)});
                } else {
                    dropped++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[SeriesEngine] RocksDB 查询失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            throw new IllegalStateException("RocksDB 时序曲线查询失败 target=" + target + ": " + e.getMessage(), e);
        }
        // 坏点被静默丢弃会让曲线残缺却看起来像「没数据」，必须留下可观测痕迹
        if (dropped > 0) {
            log.warn("[SeriesEngine] 丢弃无法解析的 RocksDB 点 target={} monitor={} metric={} 丢弃={} 保留={}",
                    target, monitorId, metric, dropped, points.size());
        }
        return SeriesEngine.downsample(points, SeriesEngine.windowMillis(window), MAX_SERIES_POINTS);
    }

    /**
     * 解析本次查询唯一对应的指标名。
     *
     * @param target    measurement/表名
     * @param monitorId 监控目标标识
     * @return 唯一指标名；无数据时返回 {@code null}
     */
    private String resolveSingleMetric(String target, Long monitorId) {
        List<String> found = metrics(target, monitorId);
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        throw new IllegalArgumentException("target=" + target + " monitor=" + monitorId + " 下存在多个指标 "
                + found + "，无法确定要查询哪条曲线；请显式传入 metricName");
    }

    @Override
    public int purge(String target, Long monitorId, String metricName, long beforeMillis) {
        if (target == null) {
            throw new IllegalArgumentException("时序清理缺少必填参数: target");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (metricName != null && !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法 metricName: " + metricName);
        }
        if (!isAvailable()) {
            return 0;
        }
        // 扫描范围：能按下标收窄就收窄，否则退化为整个 target 前缀后逐个过滤
        String scanPrefix = scanPrefix(target, monitorId, metricName);
        Set<String> affectedIndexes = Wraps.linkedSet();
        int deleted = 0;
        try {
            for (Map.Entry<byte[], byte[]> entry : engine.scanBytes(datasource, bytes(scanPrefix))) {
                String[] parts = body(toString(entry.getKey()), KEY_PREFIX).split(":");
                if (parts.length != 4) {
                    continue;
                }
                String keyTarget = parts[0];
                Long keyMonitor = parseMonitor(parts[1]);
                String keyMetric = parts[2];
                if (!target.equals(keyTarget) || keyMonitor == null) {
                    continue;
                }
                if (monitorId != null && !monitorId.equals(keyMonitor)) {
                    continue;
                }
                if (metricName != null && !metricName.equals(keyMetric)) {
                    continue;
                }
                if (decodeTs(parts[3]) >= beforeMillis) {
                    continue;
                }
                engine.deleteBytes(datasource, entry.getKey());
                deleted++;
                affectedIndexes.add(indexKey(target, keyMonitor, keyMetric));
            }
            // 清理后若某指标已无数据，必须同步摘除索引，否则 metrics() 仍把它报成已写入
            for (String index : affectedIndexes) {
                String[] parts = body(index, INDEX_PREFIX).split(":");
                if (parts.length != 3) {
                    continue;
                }
                Long keyMonitor = parseMonitor(parts[1]);
                if (keyMonitor == null) {
                    continue;
                }
                String metricPrefix = pointPrefix(target, keyMonitor, parts[2]);
                if (engine.scanBytes(datasource, bytes(metricPrefix)).isEmpty()) {
                    engine.deleteBytes(datasource, bytes(index));
                }
            }
        } catch (RuntimeException e) {
            log.error("[SeriesEngine] RocksDB 保留期清理失败 target={} monitor={}", target, monitorId, e);
            throw new IllegalStateException("RocksDB 时序保留期清理失败 target=" + target + ": " + e.getMessage(), e);
        }
        log.info("[SeriesEngine] RocksDB 保留期清理 target={} monitor={} metric={} before={} 删除={} 点",
                target, monitorId, metricName, beforeMillis, deleted);
        return deleted;
    }

    /**
     * 关闭底层 RocksDB 数据源。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (engine != null) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                log.warn("[SeriesEngine] RocksDB 引擎关闭失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 从配置解析数据目录：优先 {@code path}，兼容 {@code url}。
     *
     * @param properties 配置
     * @return 数据目录，未配置返回 null
     */
    private static String path(Properties properties) {
        if (properties == null) {
            return null;
        }
        String path = properties.getProperty("path");
        return path != null && !path.isBlank() ? path : properties.getProperty("url");
    }

    /**
     * 构造点键（字符串）。
     *
     * @param target     measurement/表名
     * @param monitorId  监控目标标识
     * @param metricName 指标名
     * @param timestamp  毫秒时间戳
     * @return 键
     */
    private static String pointKey(String target, Long monitorId, String metricName, long timestamp) {
        return pointPrefix(target, monitorId, metricName) + encodeTs(timestamp);
    }

    /**
     * 构造点键前缀（字符串）。
     *
     * @param target     measurement/表名
     * @param monitorId  监控目标标识
     * @param metricName 指标名
     * @return 前缀
     */
    private static String pointPrefix(String target, Long monitorId, String metricName) {
        return KEY_PREFIX + target + ":" + monitorId + ":" + metricName + ":";
    }

    /**
     * 构造指标索引键（字符串）。
     *
     * @param target     measurement/表名
     * @param monitorId  监控目标标识
     * @param metricName 指标名
     * @return 键
     */
    private static String indexKey(String target, Long monitorId, String metricName) {
        return INDEX_PREFIX + target + ":" + monitorId + ":" + metricName;
    }

    /**
     * 计算清理扫描前缀：monitorId 与 metricName 都明确时精确到指标，否则逐层放宽。
     *
     * @param target     measurement/表名
     * @param monitorId  监控目标标识，可为 null
     * @param metricName 指标名，可为 null
     * @return 扫描前缀
     */
    private static String scanPrefix(String target, Long monitorId, String metricName) {
        if (monitorId != null && metricName != null) {
            return pointPrefix(target, monitorId, metricName);
        }
        if (monitorId != null) {
            return KEY_PREFIX + target + ":" + monitorId + ":";
        }
        return KEY_PREFIX + target + ":";
    }

    /**
     * 将毫秒时间戳编码为定长 16 位十六进制，使字典序等于数值序。
     *
     * @param timestamp 毫秒时间戳
     * @return 定长十六进制
     */
    private static String encodeTs(long timestamp) {
        return String.format("%016x", timestamp ^ Long.MIN_VALUE);
    }

    /**
     * 解码定长十六进制时间戳。
     *
     * @param hex 定长十六进制
     * @return 毫秒时间戳
     */
    private static long decodeTs(String hex) {
        return Long.parseUnsignedLong(hex, 16) ^ Long.MIN_VALUE;
    }

    /**
     * 解析监控目标标识，非法返回 null。
     *
     * @param text 文本
     * @return Long 或 null
     */
    private static Long parseMonitor(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * double 编码为 8 字节。
     *
     * @param value 值
     * @return 字节
     */
    private static byte[] encodeDouble(double value) {
        return ByteBuffer.allocate(Double.BYTES).putDouble(value).array();
    }

    /**
     * 8 字节解码为 double。
     *
     * @param raw 字节
     * @return double
     */
    private static double decodeDouble(byte[] raw) {
        return ByteBuffer.wrap(raw).getDouble();
    }

    /**
     * 取完整键去掉指定前缀后的主体。
     *
     * @param fullKey 完整键
     * @param prefix  前缀
     * @return 主体
     */
    private static String body(String fullKey, String prefix) {
        return fullKey.startsWith(prefix) ? fullKey.substring(prefix.length()) : fullKey;
    }

    /**
     * 字符串转 UTF-8 字节。
     *
     * @param value 字符串
     * @return 字节
     */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 字节转 UTF-8 字符串。
     *
     * @param value 字节
     * @return 字符串
     */
    private static String toString(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
