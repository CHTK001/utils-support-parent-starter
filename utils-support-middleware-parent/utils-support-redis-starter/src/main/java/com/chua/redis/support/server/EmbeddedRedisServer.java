package com.chua.redis.support.server;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* 嵌入式 Redis 服务器，用于本地开发和测试。
*
* <p>封装 {@code redis.embedded.RedisServer}，提供便捷的启动/停止管理。
* 依赖 scope 为 provided，生产环境不包含。</p>
*
* <h2>使用方式</h2>
* <pre>{@code
* // 默认端口 6379
* RedisServer redis = EmbeddedRedisServer.create();
* redis.start();
*
* // 自定义端口
* RedisServer redis = EmbeddedRedisServer.create(6380);
* redis.start();
*
* // 使用 builder
* RedisServer redis = EmbeddedRedisServer.builder()
*     .port(6380)
*     .maxMemory("256mb")
*     .build();
* redis.start();
* }</pre> *     .build();
* redis.start();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Getter
public class EmbeddedRedisServer {
    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisServer.class);
/**
* 底层嵌入式 Redis 服务器
     */
    private final redis.embedded.RedisServer delegate;

    /**
    * 服务器端口
     */
    private final int port;

    /**
    * 创建 embeddedredis服务端 实例
    * @param delegate delegate
    * @param port int
    * @param port 端口
     */
    private EmbeddedRedisServer(redis.embedded.RedisServer delegate, int port) {
        this.delegate = delegate;
        this.port = port;
    }

    /**
    * 创建默认端口 (6379) 的 Redis 服务器。
    *
    * @return EmbeddedRedisServer 实例
     */
    public static EmbeddedRedisServer create() {
        return create(6379);
    }

    /**
    * 创建指定端口的 Redis 服务器。
    *
    * @param port 端口号
    * @return EmbeddedRedisServer 实例
     */
    public static EmbeddedRedisServer create(int port) {
        return builder().port(port).build();
    }

    /**
    * 获取默认 构建器。
    *
    * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
    * 启动 Redis 服务器。
    *
    * @return 当前实例，支持链式调用
     */
    public EmbeddedRedisServer start() {
        try {
            delegate.start();
            log.info("嵌入式 Redis 启动: port={}", port);
        } catch (Exception e) {
            throw new RuntimeException("嵌入式 Redis 启动失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 停止 Redis 服务器。
    *
    * @return 当前实例，支持链式调用
     */
    public EmbeddedRedisServer stop() {
        try {
            delegate.stop();
            log.info("嵌入式 Redis 停止: port={}", port);
        } catch (Exception e) {
            log.warn("嵌入式 Redis 停止异常: {}", e.getMessage());
        }
        return this;
    }

    /**
    * 判断服务器是否正在运行。
    *
    * @return 运行中返回 true
     */
    public boolean isActive() {
        return delegate.isActive();
    }

    /**
    * 获取 Redis 连接地址。
    *
    * @return redis://127.0.0.1:{port}
     */
    public String getAddress() {
        return "redis://127.0.0.1:" + port;
    }

    /**
    * 获取 Redis 连接 URL（兼容 Jedis/Redisson）。
    *
    * @return redis://127.0.0.1:{port}
     */
    public String getUrl() {
        return getAddress();
    }

    /**
    * 关闭服务器（实现 auto关闭）。
     */
    public void close() {
        stop();
    }

    /**
    * 构建器 模式创建 redis服务端。
    * @author CH
    * @since 4.0.0
     */
    public static class Builder {
        /** 端口 */
        private int port = 6379;
        /** 最大值内存 */
        private String maxMemory;
        /** 参数 */
        private String[] args = new String[0];

        /**
        * 设置端口，默认 6379
        * @param port 端口
        * @return 端口的结果
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
        * 设置最大内存，如 "256mb"
        * @param maxMemory 最大内存
        * @return 最大内存的结果
         */
        public Builder maxMemory(String maxMemory) {
            this.maxMemory = maxMemory;
            return this;
        }

        /**
        * 设置额外 Redis 配置参数
        * @param args 参数
        * @return 参数的结果
         */
        public Builder args(String... args) {
            this.args = args;
            return this;
        }

        /**
        * 构建 embeddedredis服务端 实例。
        *
        * @return EmbeddedRedisServer
         */
        public EmbeddedRedisServer build() {
            try {
                redis.embedded.RedisServerBuilder redisBuilder = redis.embedded.RedisServer.builder()
                        .port(port);

                if (maxMemory != null) {
                    redisBuilder = redisBuilder.setting("maxmemory " + maxMemory);
                }

                if (args != null && args.length > 0) {
                    for (String arg : args) {
                        redisBuilder = redisBuilder.setting(arg);
                    }
                }

                return new EmbeddedRedisServer(redisBuilder.build(), port);
            } catch (Exception e) {
                throw new RuntimeException("创建嵌入式 Redis 失败: " + e.getMessage(), e);
            }
        }
    }
}
