package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import redis.clients.jedis.JedisPool;
/**
 * 基于 Jedis 的简单 Redis 数据源。
 *
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
         * @param name 名称，不允许为 null
         * @param pool 方法入参 pool
         * @return 返回值
        */
        SimpleRedisDataSource(String name, JedisPool pool) {
            this.name = name;
            this.pool = pool;
        }

        /**
         * 名称
        */
        @Override
        public String name() {
            return name;
        }

        /**
         * 获取源
        */
        @Override
        public JedisPool getSource() {
            return pool;
        }

        /**
         * 获取源
         *
         * @param type 类型
         * @return 获取源的结果
         */
        @Override
        @SuppressWarnings("unchecked")
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

        /**
         * 设置源
        */
        @Override
        public EngineDataSource<JedisPool> setSource(Object source) {
            throw new UnsupportedOperationException("运行期不支持替换数据源对象，请重新调用 addDataSource 注册新数据源");
        }

        /**
         * 获取Dialect
        */
        @Override
        public Dialect getDialect() {
            return dialect;
        }

        /**
         * 设置Dialect
        */
        @Override
        public EngineDataSource<JedisPool> setDialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /**
         * Url
        */
        @Override
        public String url() {
            return null;
        }

        /**
         * 用户名
        */
        @Override
        public String username() {
            return null;
        }

        /**
         * 密码
        */
        @Override
        public String password() {
            return null;
        }

        /**
         * 关闭
        */
        @Override
        public void close() {
            pool.close();
        }
    }
