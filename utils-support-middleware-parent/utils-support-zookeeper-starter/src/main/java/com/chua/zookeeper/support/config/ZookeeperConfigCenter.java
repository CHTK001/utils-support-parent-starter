package com.chua.zookeeper.support.config;

import com.chua.common.support.config.center.AbstractConfigCenter;
import com.chua.common.support.config.center.ConfigCenterSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper 配置中心实现。
 * <p>
 * 基于 Apache Curator 框架连接 ZooKeeper，将 ZNode 节点作为配置存储载体。
 * 支持 YAML、Properties 两种配置格式的自动解析。
 * 配置路径规则：/{@code dataId} 或 /{@code group}/{@code dataId}。
 * </p>
 * <p>
 * <b>功能特性：</b>
 * <ul>
 *   <li>通过 Curator Framework 管理 ZooKeeper 连接和会话</li>
 *   <li>支持配置命名空间隔离（通过 ConfigCenterSetting.profile）</li>
 *   <li>支持 Digest 认证（通过 ConfigCenterSetting.username/password）</li>
 *   <li>自动解析 YAML 格式和 Properties 格式的配置内容</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zookeeper")
@Slf4j
public class ZookeeperConfigCenter extends AbstractConfigCenter {

    /**
     * Curator ZooKeeper 客户端框架实例
     */
    private CuratorFramework curatorFramework;

    /**
     * 构造 ZooKeeper 配置中心。
     *
     * @param configCenterSetting 配置中心连接设置（地址、认证、超时等）
     */
    public ZookeeperConfigCenter(ConfigCenterSetting configCenterSetting) {
        super(configCenterSetting);
    }

    @Override
    /** 获取 */
    public Map<String, Object> get(String dataId) {
        if (curatorFramework == null) {
            throw new IllegalStateException("ZooKeeper 未初始化，请先调用 start() 方法启动配置中心");
        }

        try {
            // 构建 ZNode 路径：支持绝对路径和相对路径
            String configPath = dataId.startsWith("/") ? dataId : "/" + dataId;

            // 检查节点是否存在
            if (curatorFramework.checkExists().forPath(configPath) == null) {
                log.warn("配置节点不存在: {}", configPath);
                return Collections.emptyMap();
            }

            // 获取节点数据
            byte[] configData = curatorFramework.getData().forPath(configPath);
            if (configData == null || configData.length == 0) {
                log.warn("配置节点数据为空: {}", configPath);
                return Collections.emptyMap();
            }

            // 将字节数据转为字符串
            String configContent = new String(configData, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(configContent)) {
                return Collections.emptyMap();
            }

            // 解析配置内容
            return parseConfigContent(configContent, dataId);

        } catch (Exception e) {
            throw new RuntimeException("读取 ZooKeeper 配置失败: " + dataId, e);
        }
    }

    @Override
    /** 获取 */
    public Map<String, Object> get(String dataId, String group) {
        return get(group + ":" + dataId);
    }

    @Override
    /** 开始 */
    public void start() {
        try {
            // 创建指数退避重试策略
            ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(
                    1000,
                    configCenterSetting.getRetryCount(),
                    10000
            );

            // 构建 CuratorFramework
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString(configCenterSetting.getAddress())
                    .retryPolicy(retryPolicy)
                    .connectionTimeoutMs(configCenterSetting.getConnectionTimeout())
                    .sessionTimeoutMs(configCenterSetting.getReadTimeout());

            // 设置命名空间（profile 作为根路径隔离不同环境）
            if (StringUtils.isNotBlank(configCenterSetting.getProfile())) {
                builder.namespace(configCenterSetting.getProfile());
            }

            // 设置 Digest 认证
            if (StringUtils.isNotBlank(configCenterSetting.getUsername()) &&
                    StringUtils.isNotBlank(configCenterSetting.getPassword())) {
                String authString = configCenterSetting.getUsername() + ":" + configCenterSetting.getPassword();
                builder.authorization("digest", authString.getBytes(StandardCharsets.UTF_8));
            }

            curatorFramework = builder.build();

            // 启动客户端
            curatorFramework.start();

            // 等待连接建立
            boolean connected = curatorFramework.blockUntilConnected(
                    configCenterSetting.getConnectionTimeout(), TimeUnit.MILLISECONDS);

            if (!connected) {
                throw new RuntimeException("连接 ZooKeeper 超时: " + configCenterSetting.getAddress());
            }

            logStartup();

        } catch (Exception e) {
            throw new RuntimeException("ZooKeeper 配置中心启动失败", e);
        }
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        if (curatorFramework != null) {
            try {
                curatorFramework.close();
                curatorFramework = null;
                logShutdown();
            } catch (Exception e) {
                throw new RuntimeException("关闭 ZooKeeper 配置中心失败", e);
            }
        }
    }

    /**
     * 解析配置内容字符串为键值映射。
     * <p>
     * 自动识别配置格式：
     * <ul>
     *   <li>YAML 格式（包含冒号和换行）— 使用 SnakeYAML 解析</li>
     *   <li>Properties 格式（包含等号和换行）— 逐行解析</li>
     *   <li>其他格式 — 作为单个 value 返回</li>
     * </ul>
     * </p>
     *
     * @param configContent 配置内容字符串
     * @param dataId        配置标识（用于日志）
     * @return 解析后的键值映射
     */
    private Map<String, Object> parseConfigContent(String configContent, String dataId) {
        try {
            // 尝试 YAML 格式解析
            if (isYamlContent(configContent)) {
                Yaml yaml = new Yaml();
                Object load = yaml.load(configContent);
                if (load instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configMap = (Map<String, Object>) load;
                    return MapUtils.flattenMap(configMap);
                }
            }

            // 尝试 Properties 格式解析
            if (isPropertiesContent(configContent)) {
                return parsePropertiesContent(configContent);
            }

            // 纯文本格式，作为单个值返回
            return Collections.singletonMap("value", configContent);

        } catch (Exception e) {
            throw new RuntimeException("解析配置内容失败: " + dataId, e);
        }
    }

    /**
     * 判断是否为 YAML 格式内容。
     * <p>YAML 格式特征：包含冒号且包含换行符或缩进。</p>
     *
     * @param content 配置内容
     * @return true-是 YAML 格式
     */
    private boolean isYamlContent(String content) {
        return content.contains(":") && (content.contains("\n") || content.contains("  "));
    }

    /**
     * 判断是否为 Properties 格式内容。
     * <p>Properties 格式特征：包含等号和换行符。</p>
     *
     * @param content 配置内容
     * @return true-是 Properties 格式
     */
    private boolean isPropertiesContent(String content) {
        return content.contains("=") && content.contains("\n");
    }

    /**
     * 解析 Properties 格式的配置内容。
     *
     * @param content Properties 格式的字符串
     * @return 键值映射
     */
    private Map<String, Object> parsePropertiesContent(String content) {
        Map<String, Object> result = new HashMap<>();
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int index = line.indexOf("=");
            if (index > 0) {
                String key = line.substring(0, index).trim();
                String value = line.substring(index + 1).trim();
                result.put(key, value);
            }
        }
        return result;
    }
}
