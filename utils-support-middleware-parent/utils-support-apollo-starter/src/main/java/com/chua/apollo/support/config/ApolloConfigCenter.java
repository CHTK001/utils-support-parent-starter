package com.chua.apollo.support.config;

import com.chua.common.support.config.center.AbstractConfigCenter;
import com.chua.common.support.config.center.ConfigCenterSetting;
import com.chua.common.support.config.center.ConfigListener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigChangeListener;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Apollo 配置中心实现。
 * <p>
 * 基于 Apollo Java SDK 连接 Apollo 配置中心，通过命名空间（Namespace）隔离配置。
 * Apollo 原生支持配置项的键值对管理，以及配置变更的实时推送监听。
 * </p>
 * <p>
 * <b>功能特性：</b>
 * <ul>
 *   <li>通过 {@link ConfigService} 获取 Apollo 配置对象</li>
 *   <li>dataId 对应 Apollo 的命名空间（Namespace），支持自定义命名空间</li>
 *   <li>默认使用 application 命名空间（通过 {@link ConfigCenterSetting#getProfile()} 可指定）</li>
 *   <li>支持配置变更实时推送监听</li>
 *   <li>支持 JSON 格式配置值的自动解析</li>
 * </ul>
 * </p>
 * <p>
 * <b>注意：</b>Apollo 配置中心通常作为只读源，发布和删除操作仅在本地缓存生效，
 * 不会实际写入 Apollo 服务器。如需写入请使用 Apollo 管理 API。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("apollo")
public class ApolloConfigCenter extends AbstractConfigCenter {

    /**
     * 默认命名空间名称
     */
    private static final String DEFAULT_NAMESPACE = "application";

    /**
     * Apollo 配置对象，用于获取和监听配置变更
     */
    private Config apolloConfig;

    /**
     * 当前使用的命名空间
     */
    private String namespace;

    /**
     * 构造 Apollo 配置中心。
     *
     * @param configCenterSetting 配置中心连接设置（地址通过 Apollo 的 app.properties 或环境变量配置）
     */
    public ApolloConfigCenter(ConfigCenterSetting configCenterSetting) {
        super(configCenterSetting);
    }

    @Override
    public Map<String, Object> get(String dataId) {
        if (apolloConfig == null) {
            throw new IllegalStateException("Apollo 未初始化，请先调用 start() 方法启动配置中心");
        }

        // dataId 为空时使用当前命名空间
        String ns = StringUtils.isNotBlank(dataId) ? dataId : namespace;

        // 如果 dataId 与当前命名空间不同，需要获取新的 Config 对象
        Config config = getConfigForNamespace(ns);
        if (config == null) {
            return Collections.emptyMap();
        }

        // 获取该命名空间下的所有配置项
        Set<String> propertyNames = config.getPropertyNames();
        if (propertyNames == null || propertyNames.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new HashMap<>();
        for (String key : propertyNames) {
            String value = config.getProperty(key, null);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> get(String dataId, String group) {
        // Apollo 中 group 参数对应不同的 namespace
        return get(dataId);
    }

    @Override
    public void start() {
        // 确定命名空间
        this.namespace = StringUtils.isNotBlank(configCenterSetting.getProfile())
                ? configCenterSetting.getProfile() : DEFAULT_NAMESPACE;

        // 获取 Apollo Config 对象
        this.apolloConfig = ConfigService.getConfig(namespace);

        // 注册配置变更监听器
        this.apolloConfig.addChangeListener(new ConfigChangeListener() {
            @Override
            public void onChange(ConfigChangeEvent event) {
                Set<String> changedKeys = event.changedKeys();
                for (String key : changedKeys) {
                    String oldValue = event.getChange(key).getOldValue();
                    String newValue = event.getChange(key).getNewValue();
                    if (newValue == null) {
                        notifyListenerDelete(key, oldValue);
                    } else {
                        notifyListenerUpdate(key, newValue, oldValue);
                    }
                }
            }
        });

        logStartup();
        log.info("Apollo 配置中心已启动，命名空间: {}", namespace);
    }

    @Override
    public void close() throws Exception {
        if (apolloConfig != null) {
            apolloConfig = null;
            logShutdown();
        }
    }

    @Override
    public boolean isSupportPublish() {
        return false;
    }

    @Override
    public boolean publish(String dataId, String group, String key, String value) {
        log.warn("Apollo 配置中心不支持发布操作，配置仅在本地缓存生效");
        return false;
    }

    @Override
    public boolean remove(String dataId, String key) {
        log.warn("Apollo 配置中心不支持删除操作");
        return false;
    }

    /**
     * 获取指定命名空间的 Apollo Config 对象。
     *
     * @param ns 命名空间名称
     * @return Apollo Config 对象；如果参数为空则返回当前 Config
     */
    private Config getConfigForNamespace(String ns) {
        if (StringUtils.isBlank(ns) || ns.equals(namespace)) {
            return apolloConfig;
        }
        return ConfigService.getConfig(ns);
    }

    @Override
    public void addListener(String dataId, ConfigListener listener) {
        super.addListener(dataId, listener);

        // 向 Apollo 注册配置变更监听
        String ns = StringUtils.isNotBlank(dataId) ? dataId : namespace;
        Config config = getConfigForNamespace(ns);
        if (config != null) {
            config.addChangeListener(new ConfigChangeListener() {
                @Override
                public void onChange(ConfigChangeEvent event) {
                    Set<String> changedKeys = event.changedKeys();
                    for (String key : changedKeys) {
                        String oldValue = event.getChange(key).getOldValue();
                        String newValue = event.getChange(key).getNewValue();
                        if (newValue == null) {
                            listener.onDelete(key, oldValue);
                            listener.onChange(key, oldValue, null);
                        } else {
                            listener.onChange(key, oldValue, newValue);
                            listener.onUpdate(key, oldValue, newValue);
                        }
                    }
                }
            });
        }
    }
}
