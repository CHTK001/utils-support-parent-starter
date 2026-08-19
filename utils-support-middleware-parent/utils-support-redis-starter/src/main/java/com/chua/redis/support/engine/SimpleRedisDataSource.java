package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.redis.support.client.RedisClient;
import redis.clients.jedis.JedisPool;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class SimpleRedisDataSource implements EngineDataSource<JedisPool> {
        /** 名称 */
        private final String name;
        /** 池 */
        private final JedisPool pool;
        /** Dialect */
        private Dialect dialect;

        SimpleRedisDataSource(String name, JedisPool pool) {
            this.name = name;
            this.pool = pool;
        }

        @Override
        /** Name */
        public String name() {
            return name;
        }

        @Override
        /** 获取Source */
        public JedisPool getSource() {
            return pool;
        }

        @Override
        @SuppressWarnings("unchecked")
        /** 获取Source */
        public <T> T getSource(Class<T> type) {
            // 类型与底层源一致时直接返回
            if (type.isInstance(pool)) {
                return (T) pool;
            }
            // KV 视图：复用既有连接池包装出 RedisClient
            if (type == KvEngine.class) {
                return (T) new RedisClient(pool);
            }
            // 其它类型不兼容，返回 null（与 EngineDataSource 默认契约一致）
            return null;
        }

        @Override
        /** 设置Source */
        public EngineDataSource<JedisPool> setSource(Object source) {
            return this;
        }

        @Override
        /** 获取Dialect */
        public Dialect getDialect() {
            return dialect;
        }

        @Override
        /** 设置Dialect */
        public EngineDataSource<JedisPool> setDialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        @Override
        /** Url */
        public String url() {
            return null;
        }

        @Override
        /** Username */
        public String username() {
            return null;
        }

        @Override
        /** Password */
        public String password() {
            return null;
        }

        @Override
        /** 关闭 */
        public void close() {
            pool.close();
        }
    }