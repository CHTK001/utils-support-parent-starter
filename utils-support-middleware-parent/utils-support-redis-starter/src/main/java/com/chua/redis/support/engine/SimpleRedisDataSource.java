package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.kv.KvOperations;
import com.chua.redis.support.client.RedisClient;
import redis.clients.jedis.JedisPool;
/**
 * @author CH
 */

public class SimpleRedisDataSource implements EngineDataSource<JedisPool> {
        private final String name;
        private final JedisPool pool;
        private Dialect dialect;

        SimpleRedisDataSource(String name, JedisPool pool) {
            this.name = name;
            this.pool = pool;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public JedisPool getSource() {
            return pool;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getSource(Class<T> type) {
            // 类型与底层源一致时直接返回
            if (type.isInstance(pool)) {
                return (T) pool;
            }
            // KV 视图：复用既有连接池包装出 RedisClient
            if (type == KvOperations.class) {
                return (T) new RedisClient(pool);
            }
            // 其它类型不兼容，返回 null（与 EngineDataSource 默认契约一致）
            return null;
        }

        @Override
        public EngineDataSource<JedisPool> setSource(Object source) {
            return this;
        }

        @Override
        public Dialect getDialect() {
            return dialect;
        }

        @Override
        public EngineDataSource<JedisPool> setDialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        @Override
        public String url() {
            return null;
        }

        @Override
        public String username() {
            return null;
        }

        @Override
        public String password() {
            return null;
        }

        @Override
        public void close() {
            pool.close();
        }
    }