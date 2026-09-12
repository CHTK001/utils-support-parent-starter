package com.chua.nacos.support.client;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Nacos 全功能链式客户端。
 *
 * <p>封装 Nacos ConfigService + NamingService，提供配置管理和服务发现的链式 API。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建客户端
 * NacosClient client = NacosClient.builder()
 *     .serverAddr("127.0.0.1:8848")
 *     .namespace("dev")
 *     .username("nacos")
 *     .password("nacos")
 *     .build();
 * client.start();
 *
 * // 配置操作
 * String config = client.config().dataId("app.yaml").group("DEFAULT_GROUP").get();
 * client.config().dataId("app.yaml").group("DEFAULT_GROUP").put("key", "value");
 *
 * // 服务注册
 * client.naming().serviceName("my-service").ip("127.0.0.1").port(8080).register();
 *
 * // 服务发现
 * List<Instance> instances = client.naming().serviceName("my-service").selectInstances();
 *
 * // 监听配置变更
 * client.config().dataId("app.yaml").group("DEFAULT_GROUP").onChange(newConfig -> {
 *     System.out.println("配置变更: " + newConfig);
 * });
 * }</pre>tInstances();
 *
 * // 监听配置变更
 * client.config().dataId("app.yaml").group("DEFAULT_GROUP").onChange(newConfig -> {
 *     System.out.println("配置变更: " + newConfig);
 * });
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class NacosClient implements AutoCloseable {

    /** 服务器addr */
    private final String serverAddr;
    /** Namespace */
    private final String namespace;
    /** 用户名 */
    private final String username;
    /** 密码 */
    private final String password;
    /** 超时 */
    private final long timeout;

    /** 配置服务 */
    private ConfigService configService;
    /** Naming服务 */
    private NamingService namingService;
    /** 配置监听器 */
    private final Map<String, Listener> configListeners = new ConcurrentHashMap<>();

    /**
      * 创建 nacos客户端 实例
     * @param serverAddr 服务端addr
     * @param serverAddr 字符串
     * @param serverAddr 字符串
     * @param serverAddr 字符串
     * @param timeout long
     * @param namespace namespace
     * @param username 用户名
     * @param password 密码
     * @param timeout 超时
     */
    private NacosClient(String serverAddr, String namespace, String username, String password, long timeout) {
        this.serverAddr = serverAddr;
        this.namespace = namespace;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建
     *
     * @param serverAddr 服务端addr
     * @return 创建的结果
     */
    public static NacosClient create(String serverAddr) {
        return builder().serverAddr(serverAddr).build();
    }

    /**
     * 创建
     *
     * @param serverAddr 服务端addr
     * @param namespace namespace
     * @return 创建的结果
     */
    public static NacosClient create(String serverAddr, String namespace) {
        return builder().serverAddr(serverAddr).namespace(namespace).build();
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
    public NacosClient start() throws NacosException {
        Properties props = buildProperties();
        this.configService = NacosFactory.createConfigService(props);
        this.namingService = NacosFactory.createNamingService(props);
        log.info("Nacos 客户端启动: serverAddr={}, namespace={}", serverAddr, namespace);
        return this;
    }

    /**
     * 关闭
     *
     * @return 关闭的结果
     */
    public NacosClient shutdown() {
        try {
            if (configService != null) {
                for (Map.Entry<String, Listener> entry : configListeners.entrySet()) {
                    try {
                        String[] parts = entry.getKey().split(":", 2);
                        configService.removeListener(parts[0], parts[1], entry.getValue());
                    } catch (Exception ignored) {
                    }
                }
                configListeners.clear();
                configService.shutDown();
            }
            if (namingService != null) {
                namingService.shutDown();
            }
            log.info("Nacos 客户端关闭");
        } catch (Exception e) {
            log.warn("Nacos 关闭异常: {}", e.getMessage());
        }
        return this;
    }

    /**
     * 获取配置操作构建器。
     * @return 配置的结果
     */
    public ConfigOperation config() {
        return new ConfigOperation(this);
    }

    /**
     * 获取命名服务操作构建器。
     * @return 名称的结果
     */
    public NamingOperation naming() {
        return new NamingOperation(this);
    }

    @Override
    /** 关闭 */
    public void close() {
        shutdown();
    }

    // ==================== 内部方法 ====================

    /**
     * 构建属性
     *
     * @return 构建属性的结果
     */
    private Properties buildProperties() {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (username != null && !username.isEmpty()) {
            props.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (password != null && !password.isEmpty()) {
            props.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        props.setProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT, String.valueOf(timeout));
        return props;
    }

    // ==================== Builder ====================
    /**
     * 构建器类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class Builder {
        /** 服务器addr */
        private String serverAddr = "127.0.0.1:8848";
        /** Namespace */
        private String namespace;
        /** 用户名 */
        private String username;
        /** 密码 */
        private String password;
        /** 超时 */
        private long timeout = 30000;

        /**
         * 服务端addr
         *
         * @param addr addr
         * @return 服务端addr的结果
         */
        public Builder serverAddr(String addr) { this.serverAddr = addr; return this; }
        /**
         * Namespace
         *
         * @param ns ns
         * @return namespace的结果
         */
        public Builder namespace(String ns) { this.namespace = ns; return this; }
        /**
         * 用户名
         *
         * @param u u
         * @return 用户名的结果
         */
        public Builder username(String u) { this.username = u; return this; }
        /**
         * 密码
         *
         * @param p p
         * @return 密码的结果
         */
        public Builder password(String p) { this.password = p; return this; }
        /**
         * 超时
         *
         * @param ms ms
         * @return 超时的结果
         */
        public Builder timeout(long ms) { this.timeout = ms; return this; }

        /**
         * 构建
         *
         * @return 构建的结果
         */
        public NacosClient build() {
            return new NacosClient(serverAddr, namespace, username, password, timeout);
        }
    }

    // ==================== 配置操作 ====================

    /**
     * Nacos 配置操作构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class ConfigOperation {
        /** 客户端 */
        private final NacosClient client;
        /** 数据标识 */
        private String dataId;
        /** 分组 */
        private String group = "DEFAULT_GROUP";
        /** 超时MS */
        private long timeoutMs = 5000;

        ConfigOperation(NacosClient client) { this.client = client; }

        /**
         * 数据id
         *
         * @param dataId 数据标识
         * @return 数据id的结果
         */
        public ConfigOperation dataId(String dataId) { this.dataId = dataId; return this; }
        /**
         * 分组
         *
         * @param group 群体
         * @return 群体的结果
         */
        public ConfigOperation group(String group) { this.group = group; return this; }
        /**
         * 超时
         *
         * @param ms ms
         * @return 超时的结果
         */
        public ConfigOperation timeout(long ms) { this.timeoutMs = ms; return this; }

        /**
         * 获取配置内容。
         * @return 获取的结果
         */
        public String get() {
            try {
                return client.configService.getConfig(dataId, group, timeoutMs);
            } catch (NacosException e) {
                throw new NacosClientException("获取配置失败: " + dataId, e);
            }
        }

        /**
         * 获取配置值，不存在返回默认值。
         * @param defaultValue 默认值
         * @return 获取或默认的结果
         */
        public String getOrDefault(String defaultValue) {
            String val = get();
            return val != null ? val : defaultValue;
        }

        /**
         * 获取指定键的配置值。
         * @param key 键
         * @return 获取财产的结果
         */
        public String getProperty(String key) {
            return getProperty(key, null);
        }

        /**
         * 获取指定键的配置值，不存在返回默认值。
         * @param key 键
         * @param defaultValue 默认值
         * @return 获取财产的结果
         */
        public String getProperty(String key, String defaultValue) {
            String content = get();
            if (content == null) {
                return defaultValue;
            }
            Properties props = new Properties();
            try {
                props.load(new java.io.StringReader(content));
                return props.getProperty(key, defaultValue);
            } catch (Exception e) {
                return defaultValue;
            }
        }

        /**
         * 发布配置。
         * @param content 内容
         * @return 发布的结果
         */
        public boolean publish(String content) {
            try {
                return client.configService.publishConfig(dataId, group, content);
            } catch (NacosException e) {
                throw new NacosClientException("发布配置失败: " + dataId, e);
            }
        }

        /**
         * 发布键值对配置。
         * @param key 键
         * @param value 值
         * @return 放入的结果
         */
        public boolean put(String key, String value) {
            String existing = get();
            Properties props = new Properties();
            try {
                if (existing != null) {
                    props.load(new java.io.StringReader(existing));
                }
                props.setProperty(key, value);
                java.io.StringWriter sw = new java.io.StringWriter();
                props.store(sw, null);
                String content = sw.toString().replaceAll("^#.*\\n", "").trim();
                return publish(content);
            } catch (Exception e) {
                throw new NacosClientException("发布配置失败", e);
            }
        }

        /**
         * 删除配置。
         * @return 移除的结果
         */
        public boolean remove() {
            try {
                return client.configService.removeConfig(dataId, group);
            } catch (NacosException e) {
                throw new NacosClientException("删除配置失败: " + dataId, e);
            }
        }

        /**
         * 监听配置变更。
         * @param listener 监听器
         */
        public void onChange(Consumer<String> listener) {
            try {
                Listener nacosListener = new Listener() {
                    @Override
                    /** 接收配置信息 */
                    public void receiveConfigInfo(String configInfo) {
                        listener.accept(configInfo);
                    }

                    @Override
                    /** 获取执行器 */
                    public Executor getExecutor() {
                        return null;
                    }
                };
                client.configService.addListener(dataId, group, nacosListener);
                client.configListeners.put(dataId + ":" + group, nacosListener);
            } catch (Exception e) {
                throw new NacosClientException("注册监听失败: " + dataId, e);
            }
        }
    }

    // ==================== 命名服务操作 ====================

    /**
     * Nacos 命名服务操作构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class NamingOperation {
        /** 客户端 */
        private final NacosClient client;
        /** 服务名称 */
        private String serviceName;
        /** IP */
        private String ip = "127.0.0.1";
        /** 端口 */
        private int port = 8080;
        /** 权重 */
        private double weight = 1.0;
        /** Healthy */
        private boolean healthy = true;
        /** Ephemeral */
        private boolean ephemeral = true;
        /** metadata */
        private Map<String, String> metadata = new HashMap<>();

        NamingOperation(NacosClient client) { this.client = client; }

        /**
         * 服务名称
         *
         * @param name 名称
         * @return 服务名称的结果
         */
        public NamingOperation serviceName(String name) { this.serviceName = name; return this; }
        /**
         * Ip
         *
         * @param ip ip
         * @return ip的结果
         */
        public NamingOperation ip(String ip) { this.ip = ip; return this; }
        /**
         * 端口
         *
         * @param port 端口
         * @return 端口的结果
         */
        public NamingOperation port(int port) { this.port = port; return this; }
        /**
         * 权重
         *
         * @param w w
         * @return 权重的结果
         */
        public NamingOperation weight(double w) { this.weight = w; return this; }
        /**
         * Healthy
         *
         * @param h h
         * @return healthy的结果
         */
        public NamingOperation healthy(boolean h) { this.healthy = h; return this; }
        /**
         * Ephemeral
         *
         * @param e e
         * @return ephemeral的结果
         */
        public NamingOperation ephemeral(boolean e) { this.ephemeral = e; return this; }
        /**
         * Metadata
         *
         * @param m m
         * @return metadata的结果
         */
        public NamingOperation metadata(Map<String, String> m) { this.metadata = m; return this; }
        /**
         * Metadata
         *
         * @param key 键
         * @param value 值
         * @return metadata的结果
         */
        public NamingOperation metadata(String key, String value) { this.metadata.put(key, value); return this; }

        /**
         * 注册服务实例。
         */
        public void register() {
            try {
                Instance instance = new Instance();
                instance.setIp(ip);
                instance.setPort(port);
                instance.setWeight(weight);
                instance.setHealthy(healthy);
                instance.setEphemeral(ephemeral);
                instance.setMetadata(metadata);
                client.namingService.registerInstance(serviceName, instance);
                log.info("Nacos 服务注册: {} -> {}:{}", serviceName, ip, port);
            } catch (NacosException e) {
                throw new NacosClientException("注册服务失败: " + serviceName, e);
            }
        }

        /**
         * 注销服务实例。
         */
        public void deregister() {
            try {
                client.namingService.deregisterInstance(serviceName, ip, port);
                log.info("Nacos 服务注销: {} -> {}:{}", serviceName, ip, port);
            } catch (NacosException e) {
                throw new NacosClientException("注销服务失败: " + serviceName, e);
            }
        }

        /**
         * 获取健康实例列表。
         * @return 选择instances的结果
         */
        public List<com.alibaba.nacos.api.naming.pojo.Instance> selectInstances() {
            try {
                return client.namingService.selectInstances(serviceName, true);
            } catch (NacosException e) {
                throw new NacosClientException("获取实例失败: " + serviceName, e);
            }
        }

        /**
         * 获取所有实例（含不健康）。
         * @return 选择全部instances的结果
         */
        public List<com.alibaba.nacos.api.naming.pojo.Instance> selectAllInstances() {
            try {
                return client.namingService.selectInstances(serviceName, false);
            } catch (NacosException e) {
                throw new NacosClientException("获取实例失败: " + serviceName, e);
            }
        }

        /**
         * 获取一个健康实例（随机）。
         * @return 选择one的结果
         */
        public com.alibaba.nacos.api.naming.pojo.Instance selectOne() {
            try {
                return client.namingService.selectOneHealthyInstance(serviceName);
            } catch (NacosException e) {
                throw new NacosClientException("获取实例失败: " + serviceName, e);
            }
        }

        /**
         * 订阅服务变更。
         * @param listener 监听器
         */
        public void subscribe(Consumer<List<com.alibaba.nacos.api.naming.pojo.Instance>> listener) {
            try {
                client.namingService.subscribe(serviceName, event -> {
                    try {
                        listener.accept(client.namingService.selectInstances(serviceName, true));
                    } catch (NacosException e) {
                        log.warn("获取实例列表失败", e);
                    }
                });
            } catch (NacosException e) {
                throw new NacosClientException("订阅服务失败: " + serviceName, e);
            }
        }
    }

    // ==================== 异常类 ====================
    /**
     * nacos客户端异常类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class NacosClientException extends RuntimeException {
        /**
          * 创建 nacos客户端异常 实例
         * @param message 消息
         * @param cause Throwable
         * @param cause cause
         */
        public NacosClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
