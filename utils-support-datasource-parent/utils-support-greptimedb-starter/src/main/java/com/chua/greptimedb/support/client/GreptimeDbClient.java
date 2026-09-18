package com.chua.greptimedb.support.client;

import io.greptime.GreptimeDB;
import io.greptime.models.AuthInfo;
import io.greptime.options.GreptimeOptions;

/**
* greptimedb 客户端工厂，基于官方 gRPC Ingester SDK 创建 {@link GreptimeDB} 实例。
* <p>
* 客户端为线程安全的全局单例，应使用 {@link #create(String, String, String, String)} 创建后复用。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public final class GreptimeDbClient {

    /**
    * greptimedb客户端。
    */
    private GreptimeDbClient() {
    }

    /**
    * 创建 greptimedb 客户端。
    * <p>默认使用 gRPC 协议连接 4001 端口，支持无 schema 写入（自动建表）。</p>
    *
    * @param endpoint  greptimedb gRPC 端点，如 {@code 127.0.0.1:4001}
    * @param database  目标数据库名（默认 {@code public}）
    * @param username  用户名（为空表示无鉴权）
    * @param password  密码
    * @return GreptimeDB 客户端实例
    */
    public static GreptimeDB create(String endpoint, String database, String username, String password) {
        AuthInfo authInfo = (username == null || username.isEmpty())
                ? AuthInfo.noAuthorization()
                : new AuthInfo(username, password);
        GreptimeOptions opts = GreptimeOptions.newBuilder(endpoint, database)
                .authInfo(authInfo)
                .build();
        return GreptimeDB.create(opts);
    }

    /**
    * 关闭 greptimedb 客户端并释放资源。
    *
    * @param client 客户端实例
    */
    public static void close(GreptimeDB client) {
        if (client != null) {
            try {
                client.shutdownGracefully();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}
