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


@Slf4j
@Spi("redis-search")
public class RedisSearchSink implements DataSink {

    private static final ProtocolCommand FT_CREATE = () -> SafeEncoder.encode("FT.CREATE");
    private static final ProtocolCommand FT_INFO = () -> SafeEncoder.encode("FT.INFO");

    private static final String IDX_PREFIX = "idx:ds:";
    private static final String DOC_PREFIX = "ds:";

    private final Map<String, Boolean> indexCreated = new ConcurrentHashMap<>();

    private String host = "127.0.0.1";
    private int port = 6379;
    private String password;
    private int database = 0;
    private int timeout = 3000;
    private JedisPool pool;

    public RedisSearchSink() {
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    @Override
    public String type() {
        return "redis-search";
    }

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

    @Override
    public void stop() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

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

    private static Map<String, String> convertToStringMap(Map<String, Object> data) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return result;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}
