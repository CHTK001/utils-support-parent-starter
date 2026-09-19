package com.chua.chronicle.support.kv;

import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.spi.annotations.Spi;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ChronicleMapBuilder;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 基于 chronicle映射 的键值对（KV）操作实现，作为 {@link KvEngine} 的 SPI 后端。
 * <p>
 * 本实现覆盖 {@code get / put / containsKey / delete / incr} 等核心字符串型 KV 能力，
 * 以及 {@link KvEngine#findAllByPrefix(String)} 前缀查询能力。
 * 当前所选 chronicle-映射 版本（3.27ea2）未提供条目级过期时间（TTL）API，
 * 因此 {@code put(key, value, ttl)}、{@code ttl(key)}、{@code expire(key, seconds)}
 * 沿用 {@link KvEngine} 接口的默认实现并抛出 {@link UnsupportedOperationException}，
 * 与不支持 TTL 的其它后端（如 映射db）行为一致。
 * </p>
 *
 * <h2>SPI 注册</h2>
 * <pre>{@code
 * // META-INF/services/com.chua.common.support.lang.datasource.kv.KvOperations
 * // com.chua.chronicle.support.kv.ChronicleMapKv
 *
 * Properties props = new Properties();
 * props.setProperty("file", "/data/kv.dat");   // 可选，缺省为内存型
 * props.setProperty("name", "chronicle-kv");   // 可选，默认 chronicle-kv
 * props.setProperty("entries", "10000");        // 可选，默认 10000
 * KvEngine engine = ServiceProvider.of(KvEngine.class).getNewExtension("chronicle", props);
 * }</pre>ceProvider.of(KvEngine.class).getNewExtension("chronicle", props);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("chronicle")
public class ChronicleMapKv implements KvEngine, AutoCloseable {

    /**
     * 底层 chronicle映射 实例。
     */
    private final ChronicleMap<String, String> map;

    /**
     * 是否 已 关闭（关闭 后 拒绝 读写，防止 使用 已 释放 的 堆外 内存）。
     */
    private volatile boolean closed;

    /**
     * 构造基于指定 chronicle映射 的 KV 操作实现。
     *
     * @param map 底层 chronicle映射 实例，不可为 空
     */
    public ChronicleMapKv(ChronicleMap<String, String> map) {
        this.map = map;
    }

    /**
     * 构造基于 SPI 配置的 KV 操作实现，供 {@code ServiceProvider.of(KvOperations.class).getNewExtension("chronicle", props)} 调用。
     * <p>支持属性：file（持久化文件路径，可选）、name（地图名称，默认 chronicle-kv）、entries（容量，默认 10000）、
     * averageKeySize（键平均字节数，默认 32）、averageValueSize（值平均字节数，默认 512）。</p>
     *
     * @param properties SPI 配置，不可为 空
     */
    public ChronicleMapKv(Properties properties) {
        String name = properties.getProperty("name", "chronicle-kv");
        String file = properties.getProperty("file");
        long entries = Long.parseLong(properties.getProperty("entries", "10000"));
        long averageKeySize = Long.parseLong(properties.getProperty("averageKeySize", "32"));
        long averageValueSize = Long.parseLong(properties.getProperty("averageValueSize", "512"));
        this.map = buildMap(name, file != null ? new File(file) : null, entries, averageKeySize, averageValueSize);
    }

    /**
     * 构造内存型 KV 操作实现（默认名称 chronicle-kv，容量 10000）。
     */
    public ChronicleMapKv() {
        this.map = buildMap("chronicle-kv", null, 10_000L, 32L, 512L);
    }

    /**
     * 构造持久化到指定文件的 KV 操作实现。
     *
     * @param file 持久化文件路径，不可为 空
     */
    public ChronicleMapKv(File file) {
        this.map = buildMap("chronicle-kv", file, 10_000L, 32L, 512L);
    }

    /**
     * 根据参数构建 chronicle映射 实例。
     * <p>变长 String 键值必须配置近似字节数（否则 ChronicleMap 直接抛
     * "Key size in serialized form must be configured"），
     * {@code maxChunksPerEntry} 放宽到 128 以容纳远超均值的长值（3.20+ 自动跨块重分配）。</p>
     *
     * @param name             地图名称
     * @param file             持久化文件，为 空 时构建内存型
     * @param entries          预估条目数
     * @param averageKeySize   键平均序列化字节数
     * @param averageValueSize 值平均序列化字节数
     * @return ChronicleMap 实例
     */
    private static ChronicleMap<String, String> buildMap(String name, File file, long entries,
                                                         long averageKeySize, long averageValueSize) {
        ChronicleMapBuilder<String, String> builder = ChronicleMap
                .of(String.class, String.class)
                .name(name)
                .averageKeySize(averageKeySize)
                .averageValueSize(averageValueSize)
                .maxChunksPerEntry(128)
                .entries(entries);
        if (file != null) {
            try {
                return builder.createPersistedTo(file);
            } catch (IOException e) {
                throw new IllegalStateException("创建持久化 ChronicleMap 失败: " + file, e);
            }
        }
        return builder.create();
    }

    @Override
    /**
     * 获取
    */
    public String get(String key) {
        ensureOpen();
        return map.get(key);
    }

    @Override
    /**
     * 放入（value 为 空 时 等效 删除，KvEngine 契约；ChronicleMap 本身 拒绝 null 值）
    */
    public void put(String key, String value) {
        ensureOpen();
        if (value == null) {
            map.remove(key);
            return;
        }
        map.put(key, value);
    }

    @Override
    /**
     * contains键
    */
    public boolean containsKey(String key) {
        ensureOpen();
        return map.containsKey(key);
    }

    @Override
    /**
     * 删除
    */
    public boolean delete(String key) {
        ensureOpen();
        return map.remove(key) != null;
    }

    @Override
    /**
     * Incr（ChronicleMap 重写 了 compute，条 目 级 锁 内 读-改-写 原子；非 数值 旧 值 显式 抛）
    */
    public long incr(String key) {
        ensureOpen();
        String updated = map.compute(key, (k, v) -> {
            long base;
            if (v == null || v.isEmpty()) {
                base = 0L;
            } else {
                try {
                    base = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Chronicle KV 键值非数值，无法递增: " + k, e);
                }
            }
            return String.valueOf(base + 1L);
        });
        return Long.parseLong(updated);
    }

    /**
     * 查找所有以指定前缀开头的键值对。
     * <p>ChronicleMap 无原生前缀查询能力，采用线性扫描所有键；
     * 空 前缀 表 示 返回 全部 键 值 对（与 其 它 KvEngine 后端 语义 一致）。</p>
     *
     * @param prefix 键前缀，为 空 时 视 为 空 字符串（全 量）
     * @return 匹配前缀的键值对映射；无匹配时返回空 映射
     */
    @Override
    public Map<String, String> findAllByPrefix(String prefix) {
        ensureOpen();
        String p = prefix == null ? "" : prefix;
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> keys = map.keySet();
        for (String key : keys) {
            if (key.startsWith(p)) {
                result.put(key, map.get(key));
            }
        }
        return result;
    }

    @Override
    /**
     * 关闭（释放 ChronicleMap 堆外 内存 与 文件 锁，重复 调用 无 害）
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        map.close();
    }

    /**
     * 确保未关闭
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ChronicleMap KV 已 关闭");
        }
    }
}
