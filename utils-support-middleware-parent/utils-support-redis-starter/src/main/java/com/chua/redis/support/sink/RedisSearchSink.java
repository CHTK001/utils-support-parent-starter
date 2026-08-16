package com.chua.redis.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * RedisSearch 全文检索索引 Sink：基于 Redis Stack 的 FT.CREATE / FT.INFO 命令为每条 topic 建立全文索引。
 * <p>SPI 类型 {@code "redis-search"}。默认连接 {@code 127.0.0.1:6379}，可由 setter 覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("redis-search")
public class RedisSearchSink implements DataSink {

    /**
     * FT.CREATE 命令
     */
    private static final ProtocolCommand FT_CREATE = () -> SafeEncoder.encode("FT.CREATE");

    /**
     * FT.INFO 命令
     */
    private static final ProtocolCommand FT_INFO = () -> SafeEncoder.encode("FT.INFO");

    /**
     * 索引名前缀
     */
    private static final String IDX_PREFIX = "idx:ds:";

    /**
     * 文档键前缀
     */
    private static final String DOC_PREFIX = "ds:";

    /**
     * 已建过索引的 topic 集合（线程安全）
     */
    private final Map<String, Boolean> indexCreated = new ConcurrentHashMap<>();

    /**
     * Redis 主机，默认 {@code 127.0.0.1}
     */
    private String host = "127.0.0.1";

    /**
     * Redis 端口，默认 6379
     */
    private int port = 6379;

    /**
     * 鉴权密码，null 表示无密码
     */
    private String password;

    /**
     * 数据库编号
     */
    private int database = 0;

    /**
     * 连接超时（毫秒）
     */
    private int timeout = 3000;

    /**
     * Jedis 连接池
     */
    private JedisPool pool;

    /**
     * 默认构造函数（SPI 框架使用）
     */
    public RedisSearchSink() {
    }

    /**
     * 设置 Redis 主机。
     *
     * @param host IP 或域名
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * 设置 Redis 端口。
     *
     * @param port 端口号
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 设置鉴权密码。
     *
     * @param password 密码
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 设置数据库编号。
     *
     * @param database 数据库编号（0~15）
     */
    public void setDatabase(int database) {
        this.database = database;
    }

    /**
     * @return SPI 类型 {@code redis-search}
     */
    @Override
    public String type() {
        return "redis-search";
    }

    /**
     * 启动连接池。
     */
    @Override
    public void start() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        config.setMaxIdle(5);
        config.setMinIdle(1);
        if (password != null && !password.isEmpty()) {
            pool = new JedisPool(config, host, port, timeout, password, database);
        } else {
            pool = new JedisPool(config, host, port, timeout, null, database);
        }
        log.info("[RedisSearchSink] connected {}:{}", host, port);
    }

    /**
     * 关闭连接池。
     */
    @Override
    public void stop() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    /**
     * 写入一条 envelope：自动创建 topic 对应索引（首条时），HASH 存储为 {@code ds:{topic}:{traceId}}。
     *
     * @param envelope 数据信封
     * @param config   附加配置（当前未使用）
     * @return true 表示写入成功
     */
    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (pool == null || pool.isClosed()) {
            log.warn("[RedisSearchSink] not connected");
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            Map<String, Object> data = envelope.getParsed();
            if (data == null || data.isEmpty()) {
                return false;
            }
            String topic = envelope.getTopics() != null && !envelope.getTopics().isEmpty()
                    ? envelope.getTopics().iterator().next() : "default";

            String indexName = IDX_PREFIX + topic;
            if (indexCreated.putIfAbsent(indexName, true) == null) {
                tryCreateIndex(jedis, indexName, data);
            }

            String docId = DOC_PREFIX + topic + ":" +
                    (envelope.getTraceId() != null ? envelope.getTraceId() : UUID.randomUUID().toString());
            jedis.hset(docId, convertToStringMap(data));
            return true;
        } catch (Exception e) {
            log.error("[RedisSearchSink] write error", e);
            return false;
        }
    }

    /**
     * 索引自动创建逻辑：FT.INFO 失败时按 sample 字段类型推断（Number→NUMERIC，其他→TEXT）构造 FT.CREATE。
     *
     * @param jedis      Jedis 连接
     * @param indexName  索引名
     * @param sample     索引结构推断使用的样本数据
     */
    private void tryCreateIndex(Jedis jedis, String indexName, Map<String, Object> sample) {
        try {
            jedis.sendCommand(FT_INFO, SafeEncoder.encode(indexName));
        } catch (Exception e) {
            StringBuilder schema = new StringBuilder();
            schema.append("ON HASH PREFIX 1 ").append(DOC_PREFIX).append(" SCHEMA");
            for (String key : sample.keySet()) {
                Object val = sample.get(key);
                if (val instanceof Number) {
                    schema.append(" ").append(key).append(" NUMERIC");
                } else {
                    schema.append(" ").append(key).append(" TEXT");
                }
            }
            try {
                // 将 schema 按空格拆分成多个 FT.CREATE 参数，逐个编码为字节数组
                String[] schemaParts = schema.toString().split(" ");
                byte[][] args = new byte[schemaParts.length + 1][];
                args[0] = SafeEncoder.encode(indexName);
                for (int i = 0; i < schemaParts.length; i++) {
                    args[i + 1] = SafeEncoder.encode(schemaParts[i]);
                }
                jedis.sendCommand(FT_CREATE, args);
                log.info("[RedisSearchSink] index created: {}", indexName);
            } catch (Exception e2) {
                log.warn("[RedisSearchSink] create index failed (may already exist): {}", e2.getMessage());
            }
        }
    }

    /**
     * 把 {@code Map<String, Object>} 转为 HSET 可写的 {@code Map<String, String>}，跳过 null 值。
     *
     * @param data 原始数据
     * @return 字符串形式的新 map
     */
    private static Map<String, String> convertToStringMap(Map<String, Object> data) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return result;
    }

    /**
     * @return 始终返回 null（Redis 不接入 Engine）
     */
    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}
