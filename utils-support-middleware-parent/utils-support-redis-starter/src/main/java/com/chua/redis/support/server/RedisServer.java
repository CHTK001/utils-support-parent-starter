// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.redis.support.server;

import com.chua.common.support.condition.Precondition;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.utils.ArrayUtils;

/**
 * 嵌入式 Redis 服务器，用于本地开发和测试。
 *
 * <p>封装可选的 {@code redis.embedded.RedisServer}，提供便捷的启动/停止管理。
 * 未安装嵌入式实现时构建阶段给出明确错误，生产环境不携带该依赖。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 默认端口 6379
 * RedisServer redis = RedisServer.create();
 * redis.start();
 *
 * // 自定义端口
 * RedisServer redis = RedisServer.create(6380);
 * redis.start();
 *
 * // 使用 builder
 * RedisServer redis = RedisServer.builder()
 *     .port(6380)
 *     .maxMemory("256mb")
 *     .build();
 * redis.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class RedisServer {

    /**
     * 底层嵌入式 Redis 服务器
     */
    private final Object delegate;

    /**
     * 服务器端口
     */
    private final int port;

    /**
     * 创建 redis服务端 实例
     * @param delegate delegate
     * @param port int
     */
    private RedisServer(Object delegate, int port) {
        this.delegate = delegate;
        this.port = port;
    }

    /**
     * 创建默认端口 (6379) 的 Redis 服务器。
     *
     * @return RedisServer 实例
     */
    public static RedisServer create() {
        return create(6379);
    }

    /**
     * 创建指定端口的 Redis 服务器。
     *
     * @param port 端口号
     * @return RedisServer 实例
     */
    public static RedisServer create(int port) {
        return builder().port(port).build();
    }

    /**
     * 创建 构建器。
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
    public RedisServer start() {
        try {
            ReflectUtils.invoke(delegate, "start", void.class);
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
    public RedisServer stop() {
        Precondition.tryRun(() -> {
            ReflectUtils.invoke(delegate, "stop", void.class);
            log.info("嵌入式 Redis 停止: port={}", port);
        }).orElse(e -> log.warn("嵌入式 Redis 停止异常: {}", e.getMessage(), e));
        return this;
    }

    /**
     * 判断服务器是否正在运行。
     *
     * @return 运行中返回 true
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(ReflectUtils.invoke(delegate, "isActive", boolean.class));
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
        /**
         * 端口
        */
        private int port = 6379;
        /**
         * 最大值内存
        */
        private String maxMemory;
        /**
         * 参数
        */
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
         * 构建 redis服务端 实例。
         *
         * @return RedisServer
         */
        public RedisServer build() {
            try {
                Class<?> serverClass = ReflectUtils.forName("redis.embedded.RedisServer");
                if (serverClass == null) {
                    throw new IllegalStateException("未安装嵌入式 Redis 实现，请仅在测试环境加入 embedded-redis");
                }
                Object redisBuilder = ReflectUtils.invokeStatic(serverClass, "builder", Object.class, new Class<?>[0]);
                ReflectUtils.invoke(redisBuilder, "port", Object.class, int.class, port);

                if (maxMemory != null) {
                    ReflectUtils.invoke(redisBuilder, "setting", Object.class, String.class,
                            "maxmemory " + maxMemory);
                }

                if (ArrayUtils.isNotEmpty(args)) {
                    for (String arg : args) {
                        ReflectUtils.invoke(redisBuilder, "setting", Object.class, String.class, arg);
                    }
                }

                Object delegate = ReflectUtils.invoke(redisBuilder, "build", Object.class);
                if (delegate == null) {
                    throw new IllegalStateException("嵌入式 Redis 构建失败");
                }
                return new RedisServer(delegate, port);
            } catch (Exception e) {
                throw new RuntimeException("创建嵌入式 Redis 失败: " + e.getMessage(), e);
            }
        }
    }
}