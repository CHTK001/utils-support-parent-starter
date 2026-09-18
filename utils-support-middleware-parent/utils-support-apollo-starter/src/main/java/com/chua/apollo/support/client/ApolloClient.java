package com.chua.apollo.support.client;

import com.chua.common.support.converter.Converter;
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
* }</pre> // 获取命名空间的所有配置
* 映射<String, String> 全部 = 客户端.配置().namespace("application").获取属性();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Getter
public class ApolloClient implements AutoCloseable {

    /** APPID */
    private final String appId;
    /** Meta */
    private final String meta;
    /** Namespaces */
    private final List<String> namespaces;
    /** 配置缓存 */
    private final Map<String, Config> configCache = new ConcurrentHashMap<>();

    /**
    * 创建 apollo客户端 实例
    * @param appId appid
    * @param appId 字符串
    * @param namespaces 列表
    * @param namespaces namespaces
    * @param meta meta
    */
    private ApolloClient(String appId, String meta, List<String> namespaces) {
        this.appId = appId;
        this.meta = meta;
        this.namespaces = namespaces;
    }

    // ==================== 工厂方法 ====================

    /**
    * 创建
    *
    * @param appId appid
    * @param meta meta
    * @return 创建的结果
    */
    public static ApolloClient create(String appId, String meta) {
        return builder().appId(appId).meta(meta).build();
    }

    /**
    * 构建器
    *
    * @return 构建器的结果
    */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 启动/停止 ====================

    /**
    * 开始
    *
    * @return 启动的结果
    */
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
    * @return 配置的结果
    */
    public ConfigOperation config() {
        return new ConfigOperation(this);
    }

    @Override
    /** 关闭 */
    public void close() {
        configCache.clear();
        log.info("Apollo 客户端关闭");
    }

    // ==================== Builder ====================
    /**
    * 构建器类。
    *
    * @author CH
    * @since 4.0.0
    */

    public static class Builder {
        /** APPID */
        private String appId;
        /** Meta */
        private String meta;
        /** Namespaces */
        private List<String> namespaces = new ArrayList<>(List.of("application"));

        /**
        * appid
        *
        * @param appId appid
        * @return appId的结果
        */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }
        /**
        * Meta
        *
        * @param meta meta
        * @return meta的结果
        */
        public Builder meta(String meta) {
            this.meta = meta;
            return this;
        }

        /**
        * Namespaces
        *
        * @param namespaces namespaces
        * @return namespaces的结果
        */
        public Builder namespaces(String... namespaces) {
            this.namespaces = new ArrayList<>(List.of(namespaces));
            return this;
        }

        /**
        * Namespaces
        *
        * @param namespaces namespaces
        * @return namespaces的结果
        */
        public Builder namespaces(List<String> namespaces) {
            this.namespaces = new ArrayList<>(namespaces);
            return this;
        }

        /**
        * 添加Namespace
        *
        * @param namespace namespace
        * @return 添加namespace的结果
        */
        public Builder addNamespace(String namespace) {
            this.namespaces.add(namespace);
            return this;
        }

        /**
        * 构建
        *
        * @return 构建的结果
        */
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
    * @author CH
    * @since 4.0.0
    */
    public static class ConfigOperation {
        /** 客户端 */
        private final ApolloClient client;
        /** Namespace */
        private String namespace = "application";

        ConfigOperation(ApolloClient client) { this.client = client; }

        /**
        * 指定命名空间。
        * @param namespace namespace
        * @return namespace的结果
        */
        public ConfigOperation namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
        * 获取配置值。
        * @param key 键
        * @return 获取财产的结果
        */
        public String getProperty(String key) {
            return getProperty(key, null);
        }

        /**
        * 获取配置值，不存在返回默认值。
        * @param key 键
        * @param defaultValue 默认值
        * @return 获取财产的结果
        */
        public String getProperty(String key, String defaultValue) {
            Config config = getConfig();
            return config.getProperty(key, defaultValue);
        }

        /**
        * 获取指定类型的配置值。
        * @param key 键
        * @param defaultValue 默认值
        * @param type 类型
        * @return 获取财产的结果
        */
        @SuppressWarnings("unchecked")
        public <T> T getProperty(String key, T defaultValue, Class<T> type) {
            Config config = getConfig();
            String value = config.getProperty(key, defaultValue != null ? defaultValue.toString() : null);
            if (value == null) {
                return defaultValue;
            }
            // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
            Object converted = Converter.convertIfNecessary(value, type);
            if (converted != null) {
                return (T) converted;
            }
            return defaultValue;
        }

        /**
        * 获取所有配置属性。
        * @return 获取属性的结果
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
        * 获取所有配置（映射 格式）。
        * @return 获取属性映射的结果
        */
        public Map<String, String> getPropertiesMap() {
            Map<String, String> map = new LinkedHashMap<>();
            Properties props = getProperties();
            props.forEach((k, v) -> map.put((String) k, (String) v));
            return map;
        }

        /**
        * 判断配置键是否存在。
        * @param key 键
        * @return contains财产的结果
        */
        public boolean containsProperty(String key) {
            Config config = getConfig();
            return config.getPropertyNames().contains(key);
        }

        /**
        * 监听整个命名空间的配置变更。
        * @param listener 监听器
        */
        public void onChange(Consumer<ConfigChangeEvent> listener) {
            Config config = getConfig();
            config.addChangeListener(listener::accept);
        }

        /**
        * 监听指定键的配置变更。
        * @param key 键
        * @param listener 监听器
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
        * @param listener 监听器
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

        /**
        * 获取配置
        *
        * @return 获取配置的结果
        */
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
    /**
    * apollo客户端异常类。
    *
    * @author CH
    * @since 4.0.0
    */

    public static class ApolloClientException extends RuntimeException {
        /**
        * 创建 apollo客户端异常 实例
        * @param message 消息
        */
        public ApolloClientException(String message) {
            super(message);
        }

        /**
        * 创建 apollo客户端异常 实例
        * @param message 消息
        * @param cause Throwable
        * @param cause cause
        */
        public ApolloClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
