package com.chua.redis.support.config;

import com.chua.common.support.config.center.AbstractConfigCenter;
import com.chua.common.support.config.center.ConfigCenterSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


/**
 * Redis 配置中心实现。
 * <p>
 * 基于 Jedis 连接 Redis 服务器，将 Redis 的 String 类型键值对作为配置存储。
 * dataId 对应 Redis 的 key，配置值支持 JSON、Properties 和纯文本三种格式。
 * 通过 profile 参数指定 Redis 数据库索引（0-15），实现多环境配置隔离。
 * </p>
 * <p>
 * <b>功能特性：</b>
 * <ul>
 *   <li>通过 JedisPool 连接池管理 Redis 连接</li>
 *   <li>支持 Redis 密码认证</li>
 *   <li>profile 参数映射为 Redis 数据库索引（默认 0）</li>
 *   <li>自动识别 JSON、Properties 格式并解析为键值映射</li>
 *   <li>内置连接健康检测（PING）</li>
 * </ul>
 * </p>
 *
 * @author CH
 */
@Spi("redis")
@Slf4j
public class RedisConfigCenter extends AbstractConfigCenter {

    /**

     * * Jedis 连接池实例

     */
    private JedisPool jedisPool;

    /**
     * 构造 Redis 配置中心。
     *
     * @param configCenterSetting 配置中心连接设置（地址、密码、超时等）
     */
    public RedisConfigCenter(ConfigCenterSetting configCenterSetting) {
        super(configCenterSetting);
    }

    @Override
    public Map<String, Object> get(String dataId) {
        if (jedisPool == null) {
            throw new IllegalStateException("Redis 未初始化，请先调用 start() 方法启动配置中心");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            // 从 Redis 获取配置值
            String configContent = jedis.get(dataId);

            if (StringUtils.isBlank(configContent)) {
                log.warn("Redis 配置不存在或为空: {}", dataId);
                return Collections.emptyMap();
            }

            // 解析配置内容
            return parseConfigContent(configContent, dataId);

        } catch (JedisException e) {
            throw new RuntimeException("读取 Redis 配置失败: " + dataId, e);
        }
    }

    @Override
    public Map<String, Object> get(String dataId, String group) {
        // Redis 不支持分组概念，group 参数无效，直接按 dataId 查询
        return get(dataId);
    }

    @Override
    public void start() {
        try {
            // 解析 Redis 地址
            String[] addressParts = parseRedisAddress(configCenterSetting.getAddress());
            String host = addressParts[0];
            int port = Integer.parseInt(addressParts[1]);

            // 创建连接池配置
            JedisPoolConfig poolConfig = createPoolConfig();

            // 构建 Jedis 客户端配置
            DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder()
                    .database(parseDatabase(configCenterSetting.getProfile()))
                    .connectionTimeoutMillis(configCenterSetting.getConnectionTimeout())
                    .socketTimeoutMillis(configCenterSetting.getReadTimeout());

            // 设置密码认证
            if (StringUtils.isNotBlank(configCenterSetting.getPassword())) {
                configBuilder.password(configCenterSetting.getPassword());
            }

            JedisClientConfig jedisClientConfig = configBuilder.build();
            HostAndPort hostAndPort = new HostAndPort(host, port);

            // 创建连接池
            jedisPool = new JedisPool(poolConfig, hostAndPort, jedisClientConfig);

            // 测试连接
            testConnection();

            logStartup();

        } catch (Exception e) {
            throw new RuntimeException("Redis 配置中心启动失败", e);
        }
    }

    @Override
    public void close() throws Exception {
        if (jedisPool != null) {
            try {
                jedisPool.close();
                jedisPool = null;
                logShutdown();
            } catch (Exception e) {
                throw new RuntimeException("关闭 Redis 配置中心失败", e);
            }
        }
    }

    /**
     * 解析 Redis 地址为 host 和 port。
     * <p>支持格式：host:port、host（默认端口 6379）</p>
     *
     * @param address Redis 地址字符串
     * @return [host, port]
     */
    private String[] parseRedisAddress(String address) {
        if (StringUtils.isBlank(address)) {
            return new String[]{"localhost", "6379"};
        }

        String[] parts = address.split(":");
        if (parts.length == 1) {
            return new String[]{parts[0], "6379"};
        } else if (parts.length == 2) {
            return parts;
        } else {
            throw new IllegalArgumentException("非法的 Redis 地址格式: " + address);
        }
    }

    /**
     * 解析 profile 参数为 Redis 数据库索引。
     * <p>profile 可作为 Redis 数据库索引（0-15），用于多环境配置隔离。</p>
     *
     * @param profile 环境标识，为数字时作为数据库索引
     * @return Redis 数据库索引，默认 0
     */
    private int parseDatabase(String profile) {
        if (StringUtils.isBlank(profile)) {
            return 0;
        }
        try {
            return Integer.parseInt(profile);
        } catch (NumberFormatException e) {
            log.warn("无法解析 Redis 数据库索引: {}，使用默认值 0", profile);
            return 0;
        }
    }

    /**
     * 创建 Jedis 连接池配置。
     *
     * @return 连接池配置
     */
    private JedisPoolConfig createPoolConfig() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();

        // 最大连接数
        poolConfig.setMaxTotal(20);
        // 最大空闲连接数
        poolConfig.setMaxIdle(10);
        // 最小空闲连接数
        poolConfig.setMinIdle(2);
        // 获取连接时检测可用性
        poolConfig.setTestOnBorrow(true);
        // 归还连接时检测可用性
        poolConfig.setTestOnReturn(true);
        // 空闲时检测连接可用性
        poolConfig.setTestWhileIdle(true);
        // 获取连接的最大等待时间
        poolConfig.setMaxWaitMillis(configCenterSetting.getReadTimeout());

        return poolConfig;
    }

    /**

     * * 测试 Redis 连接是否正常。

     */
    private void testConnection() {
        try (Jedis jedis = jedisPool.getResource()) {
            String pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new RuntimeException("Redis 连接测试失败，ping 响应异常: " + pong);
            }
        } catch (Exception e) {
            throw new RuntimeException("Redis 连接测试失败", e);
        }
    }

    /**
     * 解析配置内容字符串为键值映射。
     * <p>
     * 自动识别配置格式：
     * <ul>
     *   <li>JSON 格式（以 { 开头）— 简单解析键值对</li>
     *   <li>Properties 格式（包含等号和换行）— 使用 java.util.Properties 解析</li>
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
            // 尝试 JSON 格式解析
            if (isJsonContent(configContent)) {
                return parseJsonContent(configContent);
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
     * 判断是否为 JSON 格式内容。
     * <p>JSON 格式特征：以 { 或 [ 开头，以 } 或 ] 结尾。</p>
     *
     * @param content 配置内容
     * @return true-是 JSON 格式
     */
    private boolean isJsonContent(String content) {
        String trimmed = content.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"));
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
     * 解析 JSON 格式的配置内容。
     * <p>使用简单的字符串解析方式提取键值对，避免引入 Jackson/Gson 等重依赖。</p>
     *
     * @param content JSON 格式的字符串
     * @return 键值映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonContent(String content) {
        try {
            Map<String, Object> result = new HashMap<>();

            // 简单 JSON 对象解析
            if (content.trim().startsWith("{") && content.trim().endsWith("}")) {
                String jsonContent = content.trim().substring(1, content.trim().length() - 1);
                String[] pairs = jsonContent.split(",");

                for (String pair : pairs) {
                    String[] keyValue = pair.split(":");
                    if (keyValue.length == 2) {
                        String key = keyValue[0].trim().replaceAll("\"", "");
                        String value = keyValue[1].trim().replaceAll("\"", "");
                        result.put(key, value);
                    }
                }
            }

            return MapUtils.flattenMap(result);
        } catch (Exception e) {
            log.warn("JSON 配置解析失败，返回原始内容: {}", e.getMessage());
            return Collections.singletonMap("value", content);
        }
    }

    /**
     * 解析 Properties 格式的配置内容。
     *
     * @param content Properties 格式的字符串
     * @return 键值映射
     */
    private Map<String, Object> parsePropertiesContent(String content) {
        Map<String, Object> result = new HashMap<>();
        Properties properties = new Properties();

        try {
            // 使用 java.util.Properties 标准加载
            StringReader reader = new StringReader(content);
            properties.load(reader);

            for (String key : properties.stringPropertyNames()) {
                result.put(key, properties.getProperty(key));
            }
        } catch (Exception e) {
            // Properties 加载失败时，回退到逐行解析
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
        }

        return result;
    }
}
