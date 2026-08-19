package com.chua.apollo.support.discovery;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.*;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigChangeListener;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Apollo 服务发现实现类。
 * 该类通过读取 Apollo 配置中心的数据来实现服务的注册与发现功能。
 * 注意：此实现主要作为只读模式使用，支持从配置变更中监听服务更新。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("apollo")
public class ApolloServiceDiscovery extends AbstractServiceDiscovery {

    /**
     * 默认的 Apollo 命名空间名称，用于存储服务发现相关配置。
     */
    private static final String APOLLO_NAMESPACE = "discovery";

    /**
     * Apollo 配置对象实例，用于获取和监听配置变化。
     */
    private Config apolloConfig;

    /**
     * 构造函数，初始化服务发现选项。
     *
     * @param discoveryOption 服务发现配置选项
     */
    public ApolloServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    /**
     * 构造函数，初始化服务发现选项并指定集群名称。
     *
     * @param discoveryOption 服务发现配置选项
     * @param clusterName     集群名称
     */
    public ApolloServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    /**
     * 启动服务发现功能。
     * 加载所有当前配置项，并添加监听器以响应配置变更。
     */
    @Override
    public void start() {
        // 确定使用的命名空间，优先使用配置选项中的数据库名，否则使用默认命名空间
        String namespace;
        if (discoveryOption.getDatabase() != null) {
            namespace = discoveryOption.getDatabase();
        } else {
            namespace = APOLLO_NAMESPACE;
        }

        // 获取 Apollo 配置对象
        apolloConfig = ConfigService.getConfig(namespace);

        // 加载所有现有配置
        loadAll();

        // 添加配置变更监听器
        apolloConfig.addChangeListener(new ConfigChangeListener() {
            @Override
            /** OnChange */
            public void onChange(ConfigChangeEvent event) {
                Set<String> changedKeys = event.changedKeys();
                for (String key : changedKeys) {
                    String json = apolloConfig.getProperty(key, "");
                    refreshKey(key, json);
                }
            }
        });
    }

    /**
     * 加载 Apollo 配置中的所有服务发现条目。
     * 遍历所有属性键，解析 JSON 并缓存到本地。
     */
    private void loadAll() {
        if (apolloConfig == null) {
            return;
        }
        Set<String> propertyNames = apolloConfig.getPropertyNames();
        for (String key : propertyNames) {
            String json = apolloConfig.getProperty(key, "");
            refreshKey(key, json);
        }
    }

    /**
     * 刷新单个配置键对应的服务发现信息。
     * 将 JSON 字符串解析为 Discovery 对象并添加到缓存中。
     *
     * @param key   配置键
     * @param json  配置值（JSON 格式）
     */
    private void refreshKey(String key, String json) {
        if (StringUtils.isNullOrEmpty(json)) {
            return;
        }
        try {
            Discovery d = Json.fromJson(json, Discovery.class);
            addToCache(key, d);
        } catch (Exception e) {
            log.warn("Failed to parse discovery entry key={}", key, e);
        }
    }

    /**
     * 注册服务。
     * 由于 Apollo 配置中心通常作为只读源，此方法仅记录警告并执行本地缓存操作。
     *
     * @param path      服务路径
     * @param discovery 服务发现信息对象
     * @return 当前服务发现实例
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        log.warn("Apollo discovery is read-only, registerService is a no-op");
        discovery.setUriSpec(path);
        addToCache(path, discovery);
        return this;
    }

    /**
     * 注销服务。
     * 由于 Apollo 配置中心通常作为只读源，此方法不执行实际注销操作。
     *
     * @param path      服务路径
     * @param discovery 服务发现信息对象
     */
    @Override
    protected void doUnregister(String path, Discovery discovery) {
        log.warn("Apollo discovery is read-only, unregisterService is a no-op");
    }

    /**
     * 更新服务信息。
     * 由于 Apollo 配置中心通常作为只读源，此方法不执行实际更新操作。
     *
     * @param path         服务路径
     * @param oldDiscovery 旧的服务发现信息
     * @param newDiscovery 新的服务发现信息
     */
    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        log.warn("Apollo discovery is read-only, updateService is a no-op");
    }

    /**
     * 检查是否支持订阅功能。
     *
     * @return true 表示支持订阅
     */
    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    /**
     * 订阅特定服务名的变更事件。
     * 当 Apollo 配置发生变化时，通知监听器。
     *
     * @param serviceName 服务名称
     * @param listener    服务发现监听器
     */
    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        apolloConfig.addChangeListener(new ConfigChangeListener() {
            @Override
            /** OnChange */
            public void onChange(ConfigChangeEvent event) {
                Set<String> changedKeys = event.changedKeys();
                for (String key : changedKeys) {
                    String json = apolloConfig.getProperty(key, "");
                    try {
                        Discovery d = Json.fromJson(json, Discovery.class);
                        listener.listen(serviceName, d, Event.UPDATE);
                    } catch (Exception e) {
                        log.warn("Subscribe parse error", e);
                    }
                }
            }
        });
    }

    /**
     * 关闭服务发现功能。
     * 清理本地缓存数据。
     */
    @Override
    public void close() {
        clearCache();
    }
}
