package com.chua.greptimedb.support.client;

import io.greptime.GreptimeDB;
import io.greptime.models.AuthInfo;
import io.greptime.options.GreptimeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * greptimedb 客户端工厂，基于官方 gRPC Ingester SDK 创建 {@link GreptimeDB} 实例。
 * <p>
 * 每次 {@link #create(String, String, String, String)} 调用都会新建一个客户端，
 * 实例本身线程安全，<b>创建后应长期复用</b>（由 {@code GreptimeDbEngineDataSource} 持有），
 * 不再使用时通过 {@link #close(GreptimeDB)} 或数据源 {@code close()} 释放。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class GreptimeDbClient {

    /**
     * 日志记录器
     */
    private static final Logger log = LoggerFactory.getLogger(GreptimeDbClient.class);

    /**
     * 缺省数据库名，与 {@link com.chua.greptimedb.support.engine.GreptimeDbEngine} 保持一致
     */
    private static final String DEFAULT_DATABASE = "public";

    /**
     * greptimedb客户端工厂，禁止实例化。
     */
    private GreptimeDbClient() {
    }

    /**
     * 创建 greptimedb 客户端。
     * <p>默认使用 gRPC 协议连接 4001 端口，支持无 schema 写入（自动建表）。
     * 用户名非空时启用 Basic 鉴权，为空则按无鉴权集群创建。</p>
     *
     * @param endpoint greptimedb gRPC 端点，如 {@code 127.0.0.1:4001}，不可为空
     * @param database  目标数据库名（为空取默认 {@code public}）
     * @param username  用户名（为空表示无鉴权）
     * @param password  密码
     * @return GreptimeDB 客户端实例，非 空
     * @throws IllegalArgumentException 端点为空或主机字面量非法时抛出
     */
    public static GreptimeDB create(String endpoint, String database, String username, String password) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("GreptimeDB gRPC 端点不能为空");
        }
        // 复用 JDBC 侧的主机解析做前置校验，避免把非法端点推迟到首次写入才暴露
        GreptimeJdbcClient.hostOf(endpoint);
        String db = database == null || database.isBlank() ? DEFAULT_DATABASE : database.trim();
        AuthInfo authInfo = (username == null || username.isEmpty())
                ? AuthInfo.noAuthorization()
                : new AuthInfo(username, password);
        GreptimeOptions opts = GreptimeOptions.newBuilder(endpoint.trim(), db)
                .authInfo(authInfo)
                .build();
        return GreptimeDB.create(opts);
    }

    /**
     * 关闭 greptimedb 客户端并释放资源。
     * <p>优雅关闭失败不影响上层资源回收流程，但会记录告警日志，禁止静默吞掉。</p>
     *
     * @param client 客户端实例，可为 空
     */
    public static void close(GreptimeDB client) {
        if (client == null) {
            return;
        }
        try {
            client.shutdownGracefully();
        } catch (Exception e) {
            log.warn("GreptimeDB gRPC 客户端优雅关闭失败: {}", e.getMessage(), e);
        }
    }
}
