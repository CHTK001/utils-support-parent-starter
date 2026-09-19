package com.chua.redis.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.redis.support.command.RedisCommandHandler;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 基础引擎，提供数据源管理、连接池和基础工具方法。
 * <p>
 * 不实现 {@link com.chua.common.support.lang.datasource.engine.Engine} 接口，
 * 仅作为 {@link RediSearchEngine} 的基类使用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RedisEngine {

    /**
     * 数据源映射表。
     */
    protected final Map<String, EngineDataSource<JedisPool>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称。
     */
    protected String defaultDataSourceName;

    /**
     * 添加数据源（内部实现，供子类调用）。
     *
     * @param name        数据源名称
     * @param dataSource  数据源封装
     * @param <T>         底层源类型
     * @return 执行添加数据源的结果
     */
    protected <T> void doAddDataSource(String name, EngineDataSource<T> dataSource) {
        Object src = dataSource.getSource();
        if (src instanceof JedisPool) {
            dataSources.put(name, new SimpleRedisDataSource(name, (JedisPool) src));
        } else if (src instanceof String) {
            dataSources.put(name, new SimpleRedisDataSource(name, createPool((String) src)));
        } else {
            throw new IllegalArgumentException("RedisEngine仅支持JedisPool或连接URL字符串");
        }
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
    }

    /**
     * 添加数据源（便捷方法）。
     *
     * @param name 数据源名称
     * @param host 主机地址
     * @param port 端口号
     * @return this
     */
    public RedisEngine addDataSource(String name, String host, int port) {
        return addDataSource(name, host, port, null);
    }

    /**
     * 添加数据源（支持隧道）。
     *
     * @param name 数据源名称
     * @param host 主机地址
     * @param port 端口号
     * @param tunnel 隧道实例
     * @return this
     */
    public RedisEngine addDataSource(String name, String host, int port, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        int targetPort = port;
        String targetHost = host;

        if (tunnel != null) {
            int tunnelPort = tunnel.open();
            if (tunnelPort > 0) {
                targetPort = tunnelPort;
                targetHost = "127.0.0.1";
            }
        }

        JedisPool pool = new JedisPool(targetHost, targetPort);
        SimpleRedisDataSource dataSource = new SimpleRedisDataSource(name, pool);
        dataSources.put(name, dataSource);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 设置默认数据源名称（内部实现，供子类调用）。
     *
     * @param name 数据源名称
     */
    protected void doSetDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
    }

    /**
     * 获取数据源。
     *
     * @param name 数据源名称
     * @param <T>  底层源类型
     * @return 数据源封装
     */
    public <T> EngineDataSource<T> getDataSource(String name) {
        return (EngineDataSource<T>) dataSources.get(name);
    }

    /**
     * 获取默认数据源。
     *
     * @param <T> 底层源类型
     * @return 数据源封装
     */
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    /**
     * 创建连接池。
     *
     * @param url 连接URL
     * @return JedisPool 实例
     */
    protected JedisPool createPool(String url) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        config.setMaxIdle(4);
        if (url == null || url.isBlank()) {
            return new JedisPool(config, "localhost", 6379);
        }
        String trimmed = url.trim();
        if (trimmed.contains("://")) {
            // Jedis 原生解析 redis(s)://[user:pass@]host:port[/db]
            return new JedisPool(config, trimmed);
        }
        String host = trimmed;
        int port = 6379;
        int idx = trimmed.lastIndexOf(':');
        if (idx > 0 && idx < trimmed.length() - 1) {
            host = trimmed.substring(0, idx);
            port = Integer.parseInt(trimmed.substring(idx + 1));
        }
        return new JedisPool(config, host, port);
    }

    /**
     * 获取连接池。
     *
     * @param name 数据源名称
     * @return JedisPool 实例
     */
    protected JedisPool getPool(String name) {
        EngineDataSource<JedisPool> eds = dataSources.get(name);
        if (eds == null) {
            throw new IllegalArgumentException("Redis数据源未找到: " + name);
        }
        return eds.getSource();
    }

    /**
     * 获取连接池（公开方法，供搜索引擎元数据使用）。
     *
     * @param name 数据源名称
     * @return JedisPool 实例
     */
    public JedisPool getPoolPublic(String name) {
        return getPool(name);
    }

    /**
     * 关闭引擎，释放所有连接池。
     */
    public void close() {
        for (EngineDataSource<JedisPool> eds : dataSources.values()) {
            try {
                eds.getSource().close();
            } catch (Exception e) {
                log.warn("关闭Redis连接池失败: " + e.getMessage());
            }
        }
        dataSources.clear();
    }

    /**
     * 执行 Redis 命令，通过 SPI 分发到对应命令处理器。
     *
     * <p>支持 SET / GET / DEL / HSET / EXPIRE / INCR 等常见命令，
     * 未知命令抛 {@link UnsupportedOperationException}。</p>
     *
     * @param ql     Redis 命令，如 {@code SET key value}
     * @param params 额外参数，追加到命令之后
     * @return 受影响行数 / 命中数量
     */
    public int execute(String ql, Object... params) {
        // 空命令保护
        if (ql == null || ql.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis 命令不能为空");
        }
        String[] tokens = ql.trim().split("\\s+");
        String command = tokens[0].toUpperCase();
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            args.add(tokens[i]);
        }
        for (Object param : params) {
            args.add(String.valueOf(param));
        }
        // 通过 SPI 加载命令处理器
        RedisCommandHandler handler = ServiceProvider.of(RedisCommandHandler.class).getExtension(command);
        if (handler == null) {
            throw new UnsupportedOperationException("不支持的 Redis 命令: " + command);
        }
        try (Jedis jedis = getPool(defaultDataSourceName).getResource()) {
            return handler.execute(jedis, args);
        }
    }

    /**
     * 全量扫描 Redis 键并映射为实体列表。
     *
     * @param jedis        Jedis 连接
     * @param keyPrefix    键前缀
     * @param entityClass  实体类型
     * @param <T>          实体泛型
     * @return 实体列表
     */
    protected <T> List<T> scanAll(Jedis jedis, String keyPrefix, Class<T> entityClass) {
        List<T> result = new ArrayList<>();
        String cursor = "0";
        do {
            var scanResult = jedis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(keyPrefix + ":*").count(100));
            cursor = scanResult.getCursor();
            for (String key : scanResult.getResult()) {
                Map<String, String> hash = jedis.hgetAll(key);
                if (!hash.isEmpty()) {
                    result.add(mapToEntity(hash, entityClass));
                }
            }
        } while (!"0".equals(cursor));
        return result;
    }

    /**
     * 将 Redis 哈希 映射为 Java 实体。
     *
     * @param hash         哈希 字段映射
     * @param entityClass  实体类型
     * @param <T>          实体泛型
     * @return 实体实例
     */
    protected <T> T mapToEntity(Map<String, String> hash, Class<T> entityClass) {
        try {
            T instance = ReflectUtils.instantiate(entityClass);
            for (Map.Entry<String, String> entry : hash.entrySet()) {
                String legacy = toCamelCase(entry.getKey());
                String keptCase = snakeToCamelKeepCase(entry.getKey());
                for (String propName : new String[]{keptCase, legacy}) {
                    if (propName == null || propName.isEmpty()) {
                        continue;
                    }
                    String setterName = "set" + Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
                    Method setter = findSetter(entityClass, setterName);
                    if (setter != null) {
                        ReflectUtils.invoke(instance, setter.getName(), void.class,
                                convertValue(entry.getValue(), setter.getParameterTypes()[0]));
                        break;
                    }
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("无法映射Redis hash到类型: " + entityClass.getName(), e);
        }
    }

    /**
     * 查找单参数 setter。
     *
     * @param entityClass 实体类型
     * @param setterName  setter 方法名
     * @param <T>         实体泛型
     * @return 匹配的方法，未找到返回 空
     */
    private <T> Method findSetter(Class<T> entityClass, String setterName) {
        for (Method method : entityClass.getMethods()) {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    /**
     * 下划线名转为保持原大小写的驼峰名（dept_id → deptId，deptId → deptId）。
     *
     * @param name 字段名
     * @return 驼峰名
     */
    private String snakeToCamelKeepCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /**
     * 转为camel大小写
     *
     * @param name 名称
     * @return 转为camel大小写的结果
     */
    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 转换值
     *
     * @param value 值
     * @param targetType 目标类型
     * @return 转换值的结果
     */
    private Object convertValue(String value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(value, targetType);
        if (converted != null) {
            return converted;
        }
        return value;
    }

    /**
     * 获取实体键前缀。
     *
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 键前缀
     */
    protected <T> String getKeyPrefix(Class<T> entityClass) {
        return entityClass.getSimpleName().toLowerCase();
    }
}
