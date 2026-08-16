package com.chua.apollo.support.client;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigChangeListener;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Apollo 全功能链式客户端。
 *
 * <p>封装 Apollo ConfigService，提供配置获取、监听的链式 API。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建客户端
 * ApolloClient client = ApolloClient.builder()
 *     .appId("my-app")
 *     .meta("http://apollo-server:8801")
 *     .namespaces("application", "database")
 *     .build();
 *
 * // 获取配置
 * String value = client.config().namespace("application").getProperty("db.host");
 *
 * // 监听配置变更
 * client.config().namespace("database").onChange(event -> {
 *     event.changedKeys().forEach(key -> {
 *         System.out.println(key + " = " + event.getChange(key).getNewValue());
 *     });
 * });
 *
 * // 获取命名空间的所有配置
 * Map<String, String> all = client.config().namespace("application").getProperties();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class ApolloClient implements AutoCloseable {

    private final String appId;
    private final String meta;
    private final List<String> namespaces;
    private final Map<String, Config> configCache = new ConcurrentHashMap<>();

    private ApolloClient(String appId, String meta, List<String> namespaces) {
        this.appId = appId;
        this.meta = meta;
        this.namespaces = namespaces;
    }

    // ==================== 工厂方法 ====================

    public static ApolloClient create(String appId, String meta) {
        return builder().appId(appId).meta(meta).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 启动/停止 ====================

    public ApolloClient start() {
        System.setProperty("app.id", appId);
        if (meta != null && !meta.isEmpty()) {
            System.setProperty("apollo.meta", meta);
        }

        // 预加载所有命名空间
        for (String ns : namespaces) {
            configCache.computeIfAbsent(ns, ConfigService::getConfig);
        }

        log.info("Apollo 客户端启动: appId={}, meta={}, namespaces={}", appId, meta, namespaces);
        return this;
    }

    /**
     * 获取配置操作构建器。
     */
    public ConfigOperation config() {
        return new ConfigOperation(this);
    }

    @Override
    public void close() {
        configCache.clear();
        log.info("Apollo 客户端关闭");
    }

    // ==================== Builder ====================

    public static class Builder {
        private String appId;
        private String meta;
        private List<String> namespaces = new ArrayList<>(List.of("application"));

        public Builder appId(String appId) { this.appId = appId; return this; }
        public Builder meta(String meta) { this.meta = meta; return this; }

        public Builder namespaces(String... namespaces) {
            this.namespaces = new ArrayList<>(List.of(namespaces));
            return this;
        }

        public Builder namespaces(List<String> namespaces) {
            this.namespaces = new ArrayList<>(namespaces);
            return this;
        }

        public Builder addNamespace(String namespace) {
            this.namespaces.add(namespace);
            return this;
        }

        public ApolloClient build() {
            if (appId == null || appId.isEmpty()) {
                throw new IllegalArgumentException("appId 不能为空");
            }
            return new ApolloClient(appId, meta, namespaces);
        }
    }

    // ==================== 配置操作 ====================

    /**
     * Apollo 配置操作构建器。
     */
    public static class ConfigOperation {
        private final ApolloClient client;
        private String namespace = "application";

        ConfigOperation(ApolloClient client) { this.client = client; }

        /**
         * 指定命名空间。
         */
        public ConfigOperation namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * 获取配置值。
         */
        public String getProperty(String key) {
            return getProperty(key, null);
        }

        /**
         * 获取配置值，不存在返回默认值。
         */
        public String getProperty(String key, String defaultValue) {
            Config config = getConfig();
            return config.getProperty(key, defaultValue);
        }

        /**
         * 获取指定类型的配置值。
         */
        @SuppressWarnings("unchecked")
        public <T> T getProperty(String key, T defaultValue, Class<T> type) {
            Config config = getConfig();
            if (type == Integer.class || type == int.class) {
                return (T) Integer.valueOf(config.getIntProperty(key, (Integer) defaultValue));
            }
            if (type == Long.class || type == long.class) {
                return (T) Long.valueOf(config.getLongProperty(key, (Long) defaultValue));
            }
            if (type == Boolean.class || type == boolean.class) {
                return (T) Boolean.valueOf(config.getBooleanProperty(key, (Boolean) defaultValue));
            }
            if (type == Double.class || type == double.class) {
                return (T) Double.valueOf(config.getDoubleProperty(key, (Double) defaultValue));
            }
            if (type == Float.class || type == float.class) {
                return (T) Float.valueOf(config.getFloatProperty(key, (Float) defaultValue));
            }
            String value = config.getProperty(key, defaultValue != null ? defaultValue.toString() : null);
            return value != null ? (T) value : defaultValue;
        }

        /**
         * 获取所有配置属性。
         */
        public Properties getProperties() {
            Config config = getConfig();
            Properties props = new Properties();
            Set<String> keys = config.getPropertyNames();
            if (keys != null) {
                for (String key : keys) {
                    props.setProperty(key, config.getProperty(key, ""));
                }
            }
            return props;
        }

        /**
         * 获取所有配置（Map 格式）。
         */
        public Map<String, String> getPropertiesMap() {
            Map<String, String> map = new LinkedHashMap<>();
            Properties props = getProperties();
            props.forEach((k, v) -> map.put((String) k, (String) v));
            return map;
        }

        /**
         * 判断配置键是否存在。
         */
        public boolean containsProperty(String key) {
            Config config = getConfig();
            return config.getPropertyNames().contains(key);
        }

        /**
         * 监听整个命名空间的配置变更。
         */
        public void onChange(Consumer<ConfigChangeEvent> listener) {
            Config config = getConfig();
            config.addChangeListener(listener::accept);
        }

        /**
         * 监听指定键的配置变更。
         */
        public void onChange(String key, Consumer<String> listener) {
            Config config = getConfig();
            config.addChangeListener(event -> {
                ConfigChange change = event.getChange(key);
                if (change != null) {
                    listener.accept(change.getNewValue());
                }
            });
        }

        /**
         * 监听多个键的配置变更。
         */
        public void onChangeKeys(Consumer<Map<String, String>> listener) {
            Config config = getConfig();
            config.addChangeListener(event -> {
                Map<String, String> changed = new LinkedHashMap<>();
                for (String key : event.changedKeys()) {
                    changed.put(key, event.getChange(key).getNewValue());
                }
                listener.accept(changed);
            });
        }

        private Config getConfig() {
            Config config = client.configCache.get(namespace);
            if (config == null) {
                config = ConfigService.getConfig(namespace);
                if (config != null) {
                    client.configCache.put(namespace, config);
                } else {
                    throw new ApolloClientException("获取 Apollo 配置失败: namespace=" + namespace);
                }
            }
            return config;
        }
    }

    // ==================== 异常类 ====================

    public static class ApolloClientException extends RuntimeException {
        public ApolloClientException(String message) {
            super(message);
        }

        public ApolloClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
