package com.chua.greptimedb.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import io.greptime.GreptimeDB;

/**
 * greptimedb 数据源封装，持有 {@link GreptimeDB} gRPC 客户端实例。
 * <p>
 * 供 {@link com.chua.greptimedb.support.engine.GreptimeDbEngine} 按名称管理多个 greptimedb 集群。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GreptimeDbEngineDataSource implements EngineDataSource<GreptimeDB> {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 底层 gRPC 客户端
     */
    private GreptimeDB source;

    /**
     * 连接端点
     */
    private final String url;

    /**
     * 用户名
     */
    private final String username;

    /**
     * 密码
     */
    private final String password;

    /**
     * 数据库名
     */
    private final String database;

    /**
     * 构造方法
     *
     * @param name     数据源名称
     * @param url      连接端点
     * @param username 用户名
     * @param password 密码
     * @param database 数据库名
     * @param source   greptimedb 客户端
     */
    public GreptimeDbEngineDataSource(String name, String url, String username,
                                       String password, String database, GreptimeDB source) {
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.database = database;
        this.source = source;
    }

    @Override
    /** 名称 */
    public String name() {
        return name;
    }

    @Override
    /** 获取客户端 */
    public GreptimeDB getSource() {
        return source;
    }

    @Override
    /**
     * 设置客户端
     *
     * @param source 源
     * @return 设置源的结果
     */
    @SuppressWarnings("unchecked")
    public EngineDataSource<GreptimeDB> setSource(Object source) {
        if (source instanceof GreptimeDB client) {
            this.source = client;
        }
        return this;
    }

    @Override
    /** 获取方言（非 SQL 数据源返回 空） */
    public Dialect getDialect() {
        return null;
    }

    @Override
    /** 设置方言 */
    public EngineDataSource<GreptimeDB> setDialect(Dialect dialect) {
        return this;
    }

    @Override
    /** 连接端点 */
    public String url() {
        return url;
    }

    @Override
    /** 用户名 */
    public String username() {
        return username;
    }

    @Override
    /** 密码 */
    public String password() {
        return password;
    }

    @Override
    /** 数据库名 */
    public String database() {
        return database;
    }

    @Override
    /** 关闭客户端 */
    public void close() {
        if (source != null) {
            try {
                source.shutdownGracefully();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}
