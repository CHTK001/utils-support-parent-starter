package com.chua.nacos.support.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.chua.common.support.config.center.AbstractConfigCenter;
import com.chua.common.support.config.center.ConfigCenterSetting;
import com.chua.common.support.config.center.ConfigListener;
import com.chua.common.support.config.parser.PropertiesConfigParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Nacos 配置中心实现。
 * <p>
   * 基于 Nacos 客户端 SDK 连接 Nacos 服务器，提供配置的获取、发布、删除和变更监听功能。
   * 支持 YAML、属性 两种配置格式的自动识别和解析，通过 SPI 扩展点支持更多格式。
 * </p>
 * <p>
 * <b>功能特性：</b>
 * <ul>
 *   <li>通过 Nacos ConfigService API 管理配置</li>
 *   <li>支持命名空间隔离（通过 ConfigCenterSetting.profile）</li>
 *   <li>支持用户名/密码认证</li>
 *   <li>支持 gRPC 协议（通过地址中的协议前缀切换）</li>
 *   <li>自动监听配置变更并通知本地监听器</li>
 *   <li>发布配置时自动将键值对转换为 YAML 格式存储</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("nacos")
public class NacosConfigCenter extends AbstractConfigCenter {

    /**
     * 默认分组名称
     */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * Nacos 配置服务实例
     */
    private ConfigService configService;

    /**
      * 配置内容缓存，键 为 "数据id:群体"
     */
    private final Map<String, Map<String, Object>> configContentCache = new ConcurrentHashMap<>();

    /**
     * 构造 Nacos 配置中心。
     *
     * @param configCenterSetting 配置中心连接设置（地址、命名空间、认证等）
     */
    public NacosConfigCenter(ConfigCenterSetting configCenterSetting) {
        super(configCenterSetting);
    }

    @Override
    /** 获取 */
    public Map<String, Object> get(String dataId) {
        return get(dataId, DEFAULT_GROUP);
    }

    @Override
    /** 获取 */
    public Map<String, Object> get(String dataId, String group) {
        if (configService == null) {
            throw new IllegalStateException("Nacos 未初始化，请先调用 start() 方法启动配置中心");
        }

        String grp = StringUtils.isNotBlank(group) ? group : DEFAULT_GROUP;
        try {
            String config = configService.getConfig(dataId, grp, configCenterSetting.getReadTimeout());
            if (config == null) {
                return Collections.emptyMap();
            }
            return parseConfigContent(config, dataId);
        } catch (NacosException e) {
            throw new RuntimeException("读取 Nacos 配置失败: " + dataId, e);
        }
    }

    @Override
    /** 开始 */
    public void start() {
        final Properties properties = new Properties();
        String address = configCenterSetting.getAddress();

 // 解析地址格式，支持 gRPC:// 等协议前缀
        if (address != null && address.contains("://")) {
            String[] parts = address.split("://", 2);
            properties.setProperty(PropertyKeyConst.SERVER_ADDR, parts[1]);
            if (parts[0].equalsIgnoreCase("grpc")) {
                properties.setProperty("nacos.remote.client.rpc.tls.enable", "true");
            }
        } else {
            properties.setProperty(PropertyKeyConst.SERVER_ADDR, address);
        }

        // 设置命名空间（用于多环境隔离）
        if (StringUtils.isNotBlank(configCenterSetting.getProfile())) {
            properties.setProperty(PropertyKeyConst.NAMESPACE, configCenterSetting.getProfile());
        }

        // 设置用户名密码认证
        if (StringUtils.isNotBlank(configCenterSetting.getUsername())) {
            properties.setProperty(PropertyKeyConst.USERNAME, configCenterSetting.getUsername());
        }
        if (StringUtils.isNotBlank(configCenterSetting.getPassword())) {
            properties.setProperty(PropertyKeyConst.PASSWORD, configCenterSetting.getPassword());
        }

        // 设置长轮询超时
        properties.setProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT,
                String.valueOf(configCenterSetting.getReadTimeout()));
        // 设置重试次数
        properties.setProperty(PropertyKeyConst.CONFIG_RETRY_TIME,
                String.valueOf(configCenterSetting.getRetryCount()));

        try {
            configService = NacosFactory.createConfigService(properties);
            logStartup();
        } catch (NacosException e) {
            throw new RuntimeException("启动 Nacos 配置中心失败", e);
        }
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        if (configService != null) {
            try {
                configService.shutDown();
                configService = null;
                configContentCache.clear();
                logShutdown();
            } catch (Exception e) {
                throw new RuntimeException("关闭 Nacos 配置中心失败", e);
            }
        }
    }

    @Override
    /** 发布 */
    public boolean publish(String dataId, String group, String key, String value) {
        if (configService == null) {
            throw new IllegalStateException("Nacos 配置中心未启动");
        }

        try {
            String grp = StringUtils.isNotBlank(group) ? group : DEFAULT_GROUP;
            String cacheKey = dataId + ":" + grp;

            // 获取当前配置缓存
            Map<String, Object> currentConfig = configContentCache.computeIfAbsent(cacheKey, k -> {
                Map<String, Object> config = get(dataId, grp);
                return config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
            });

            // 更新配置项
            currentConfig.put(key, value);

            // 将配置转换为 YAML 格式存储到 Nacos
            String content = convertToYaml(currentConfig);
            boolean success = configService.publishConfig(dataId, grp, content);

            if (success) {
                log.info("Nacos 配置发布成功: dataId={}, group={}, key={}", dataId, grp, key);
            } else {
                log.warn("Nacos 配置发布失败: dataId={}, group={}, key={}", dataId, grp, key);
            }
            return success;
        } catch (NacosException e) {
            log.error("Nacos 配置发布异常: dataId={}, key={}", dataId, key, e);
            return false;
        }
    }

    @Override
    /** 移除 */
    public boolean remove(String dataId, String key) {
        if (configService == null) {
            throw new IllegalStateException("Nacos 配置中心未启动");
        }

        try {
            String cacheKey = dataId + ":" + DEFAULT_GROUP;

            // 获取当前配置
            Map<String, Object> currentConfig = configContentCache.get(cacheKey);
            if (currentConfig == null) {
                currentConfig = get(dataId, DEFAULT_GROUP);
                if (currentConfig == null || currentConfig.isEmpty()) {
                    return true;
                }
                currentConfig = new LinkedHashMap<>(currentConfig);
            }

            // 移除配置项
            currentConfig.remove(key);
            configContentCache.put(cacheKey, currentConfig);

            // 将剩余配置重新发布
            String content = convertToYaml(currentConfig);
            boolean success = configService.publishConfig(dataId, DEFAULT_GROUP, content);

            if (success) {
                log.info("Nacos 配置删除成功: dataId={}, key={}", dataId, key);
            }
            return success;
        } catch (NacosException e) {
            log.error("Nacos 配置删除异常: dataId={}, key={}", dataId, key, e);
            return false;
        }
    }

    @Override
    /** 添加监听器 */
    public void addListener(String dataId, ConfigListener listener) {
        super.addListener(dataId, listener);

        // 向 Nacos 注册配置变更监听器
        try {
            configService.addListener(dataId, DEFAULT_GROUP, new Listener() {
                @Override
                /** 接收配置信息 */
                public void receiveConfigInfo(String configInfo) {
                    // 配置变更时解析新内容并通知监听器
                    Map<String, Object> newConfig = parseConfigContent(configInfo, dataId);
                    String cacheKey = dataId + ":" + DEFAULT_GROUP;
                    Map<String, Object> oldConfig = configContentCache.get(cacheKey);

                    if (oldConfig != null) {
                        // 逐项比对，通知变更
                        for (Map.Entry<String, Object> entry : newConfig.entrySet()) {
                            String newValue = entry.getValue() != null ? entry.getValue().toString() : null;
                            String oldValue = oldConfig.get(entry.getKey()) != null
                                    ? oldConfig.get(entry.getKey()).toString() : null;
                            if (!Objects.equals(newValue, oldValue)) {
                                notifyListenerUpdate(entry.getKey(), newValue, oldValue);
                            }
                        }
                        // 检查已删除的键
                        for (String oldKey : oldConfig.keySet()) {
                            if (!newConfig.containsKey(oldKey)) {
                                notifyListenerDelete(oldKey, oldConfig.get(oldKey).toString());
                            }
                        }
                    }

                    configContentCache.put(cacheKey, newConfig);
                }

                @Override
                /** 获取执行器 */
                public Executor getExecutor() {
                    return null;
                }
            });
        } catch (NacosException e) {
            log.warn("向 Nacos 注册配置监听器失败: dataId={}", dataId, e);
        }
    }

    /**
     * 解析配置内容字符串为键值映射。
     * <p>
      * 根据 数据id 的后缀（如 .yaml、.属性）选择合适的解析器。
      * 无后缀时自动检测 YAML 或 属性 格式。
     * </p>
     *
     * @param configContent 配置内容
     * @param dataId        配置标识（用于确定解析格式）
     * @return 解析后的键值映射
     */
    private Map<String, Object> parseConfigContent(String configContent, String dataId) {
        try {
            // 根据文件后缀选择解析方式
            if (isYamlConfig(dataId, configContent)) {
                Yaml yaml = new Yaml();
                Object load = yaml.load(configContent);
                if (load instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configMap = (Map<String, Object>) load;
                    return MapUtils.flattenMap(configMap);
                }
            } else if (isPropertiesConfig(dataId, configContent)) {
                PropertiesConfigParser parser = new PropertiesConfigParser();
                var propertySource = parser.parse(dataId,
                        new ByteArrayInputStream(configContent.getBytes(StandardCharsets.UTF_8)));
                return MapUtils.flattenMap(propertySource.toMap());
            }

            // 纯文本，作为值返回
            return Collections.singletonMap("value", configContent);

        } catch (Exception e) {
            log.warn("解析配置内容失败: dataId={}", dataId, e);
            return Collections.singletonMap("value", configContent);
        }
    }

    /**
     * 判断是否为 YAML 配置。
     *
     * @param dataId  配置标识
     * @param content 配置内容
     * @return true-应使用 YAML 解析
     */
    private boolean isYamlConfig(String dataId, String content) {
        if (dataId != null) {
            String lower = dataId.toLowerCase();
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
                return true;
            }
        }
        return content.contains(":") && (content.contains("\n") || content.contains("  "));
    }

    /**
      * 判断是否为 属性 配置。
     *
     * @param dataId  配置标识
     * @param content 配置内容
     * @return true-应使用 属性 解析
     */
    private boolean isPropertiesConfig(String dataId, String content) {
        if (dataId != null) {
            String lower = dataId.toLowerCase();
            if (lower.endsWith(".properties")) {
                return true;
            }
        }
        return content.contains("=") && content.contains("\n");
    }

    /**
      * 将配置 映射 转换为 YAML 格式字符串。
     *
     * @param config 配置映射
     * @return YAML 格式字符串
     */
    private String convertToYaml(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "";
        }

        Map<String, Object> nestedConfig = MapUtils.unflattenFromProperties(config);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        return yaml.dump(nestedConfig);
    }
}
