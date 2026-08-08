package com.chua.runtime.apm.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 存储配置 — 启动时一次性传入 {@link ApmStorage#start(StorageConfig)}。
 *
 * <p>键值对语义，具体键由实现定义：</p>
 * <ul>
 *   <li>{@code apm.storage.type} — 选择实现（noop/inmemory/sqlite/jdbc/otlp）</li>
 *   <li>{@code apm.storage.retention.ms} — 默认 7 天（604800000）</li>
 *   <li>{@code apm.storage.capacity} — 单表最大行数（默认 100000）</li>
 *   <li>{@code apm.storage.path} — SQLite 文件路径</li>
 *   <li>{@code apm.storage.datasource} — Spring DataSource bean name</li>
 *   <li>{@code apm.storage.otlp.endpoint} — OTLP HTTP endpoint</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageConfig {

    /** 配置项 map */
    private Map<String, String> properties = new HashMap<>();

    public StorageConfig put(String key, String value) {
        properties.put(key, value);
        return this;
    }

    public String get(String key) {
        return properties.get(key);
    }

    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        String v = properties.get(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getInt(String key, int defaultValue) {
        return (int) getLong(key, defaultValue);
    }

    /**
     * 默认配置（无操作存储）。
     */
    public static StorageConfig defaults() {
        StorageConfig c = new StorageConfig();
        c.put("apm.storage.type", ApmStorage.DEFAULT_NAME);
        c.put("apm.storage.retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000));
        c.put("apm.storage.capacity", "100000");
        return c;
    }
}