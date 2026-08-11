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
 * 基于 ChronicleMap 的键值对（KV）操作实现，作为 {@link KvEngine} 的 SPI 后端。
 * <p>
 * 本实现覆盖 {@code get / put / containsKey / delete / incr} 等核心字符串型 KV 能力，
 * 以及 {@link KvEngine#findAllByPrefix(String)} 前缀查询能力。
 * 当前所选 chronicle-map 版本（3.27ea2）未提供条目级过期时间（TTL）API，
 * 因此 {@code put(key, value, ttl)}、{@code ttl(key)}、{@code expire(key, seconds)}
 * 沿用 {@link KvEngine} 接口的默认实现并抛出 {@link UnsupportedOperationException}，
 * 与不支持 TTL 的其它后端（如 MapDB）行为一致。
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
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("chronicle")
public class ChronicleMapKv implements KvEngine {

    /**
     * 底层 ChronicleMap 实例。
     */
    private final ChronicleMap<String, String> map;

    /**
     * 构造基于指定 ChronicleMap 的 KV 操作实现。
     *
     * @param map 底层 ChronicleMap 实例，不可为 null
     */
    public ChronicleMapKv(ChronicleMap<String, String> map) {
        this.map = map;
    }

    /**
     * 构造基于 SPI 配置的 KV 操作实现，供 {@code ServiceProvider.of(KvOperations.class).getNewExtension("chronicle", props)} 调用。
     * <p>支持属性：file（持久化文件路径，可选）、name（地图名称，默认 chronicle-kv）、entries（容量，默认 10000）。</p>
     *
     * @param properties SPI 配置，不可为 null
     */
    public ChronicleMapKv(Properties properties) {
        String name = properties.getProperty("name", "chronicle-kv");
        String file = properties.getProperty("file");
        long entries = Long.parseLong(properties.getProperty("entries", "10000"));
        this.map = buildMap(name, file != null ? new File(file) : null, entries);
    }

    /**
     * 构造内存型 KV 操作实现（默认名称 chronicle-kv，容量 10000）。
     */
    public ChronicleMapKv() {
        this.map = buildMap("chronicle-kv", null, 10_000L);
    }

    /**
     * 构造持久化到指定文件的 KV 操作实现。
     *
     * @param file 持久化文件路径，不可为 null
     */
    public ChronicleMapKv(File file) {
        this.map = buildMap("chronicle-kv", file, 10_000L);
    }

    /**
     * 根据参数构建 ChronicleMap 实例。
     *
     * @param name    地图名称
     * @param file    持久化文件，为 null 时构建内存型
     * @param entries 预估条目数
     * @return ChronicleMap 实例
     */
    private static ChronicleMap<String, String> buildMap(String name, File file, long entries) {
        ChronicleMapBuilder<String, String> builder = ChronicleMap
                .of(String.class, String.class)
                .name(name)
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
    public String get(String key) {
        return map.get(key);
    }

    @Override
    public void put(String key, String value) {
        map.put(key, value);
    }

    @Override
    public boolean containsKey(String key) {
        return map.containsKey(key);
    }

    @Override
    public boolean delete(String key) {
        return map.remove(key) != null;
    }

    @Override
    public long incr(String key) {
        // 基于 ConcurrentMap.compute 在 ChronicleMap 内部锁保护下完成原子递增
        String updated = map.compute(key, (k, v) ->
                String.valueOf((v == null ? 0L : Long.parseLong(v)) + 1L));
        return Long.parseLong(updated);
    }

    /**
     * 查找所有以指定前缀开头的键值对。
     * <p>ChronicleMap 无原生前缀查询能力，采用线性扫描所有键。</p>
     *
     * @param prefix 键前缀，不可为 null
     * @return 匹配前缀的键值对映射；无匹配时返回空 Map
     */
    @Override
    public Map<String, String> findAllByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> keys = map.keySet();
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                result.put(key, map.get(key));
            }
        }
        return result;
    }
}
