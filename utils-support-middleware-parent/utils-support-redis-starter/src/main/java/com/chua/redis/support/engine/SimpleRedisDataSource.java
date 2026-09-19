package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import redis.clients.jedis.JedisPool;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class SimpleRedisDataSource implements EngineDataSource<JedisPool> {
        /**
         * 名称
        */
        private final String name;
        /**
         * 池
        */
        private final JedisPool pool;
        /**
         * Dialect
        */
        private Dialect dialect;

        /**
         * 构造方法，创建 SimpleRedis数据来源 实例。
         *
         * @param name 名称，不允许为 null
         * @param pool 方法入参 pool
         */
        SimpleRedisDataSource(String name, JedisPool pool) {
            this.name = name;
            this.pool = pool;
        }

        @Override
        /**
         * 名称
        */
        public String name() {
            return name;
        }

        @Override
        /**
         * 获取源
        */
        public JedisPool getSource() {
            return pool;
        }

        @Override
        @SuppressWarnings("unchecked")
        /**
         * 获取源
         *
         * @param type 类型
         * @return 获取源的结果
         */
        public <T> T getSource(Class<T> type) {
            // 类型与底层源一致时直接返回
            if (type.isInstance(pool)) {
                return (T) pool;
            }
 // KV 视图：Redis 客户端已通过 redisreactorengine 管理，此处返回 空
            if (type == KvEngine.class) {
                return null;
            }
 // 其它类型不兼容，返回 空（与 engine数据源 默认契约一致）
            return null;
        }

        @Override
        /**
         * 设置源
        */
        public EngineDataSource<JedisPool> setSource(Object source) {
            return this;
        }

        @Override
        /**
         * 获取Dialect
        */
        public Dialect getDialect() {
            return dialect;
        }

        @Override
        /**
         * 设置Dialect
        */
        public EngineDataSource<JedisPool> setDialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        @Override
        /**
         * Url
        */
        public String url() {
            return null;
        }

        @Override
        /**
         * 用户名
        */
        public String username() {
            return null;
        }

        @Override
        /**
         * 密码
        */
        public String password() {
            return null;
        }

        @Override
        /**
         * 关闭
        */
        public void close() {
            pool.close();
        }
    }
