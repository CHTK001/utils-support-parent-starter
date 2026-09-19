package com.chua.influxdb.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.influxdb.InfluxDB;

/**
 * influxdb 数据源封装，持有官方 {@link InfluxDB} 客户端。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InfluxDbEngineDataSource implements EngineDataSource<InfluxDB> {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 官方客户端
     */
    private final InfluxDB source;

    /**
     * 连接地址
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
     * 构造数据源。
     *
     * @param name     数据源名称
     * @param url      连接地址
     * @param username 用户名
     * @param password 密码
     * @param database 数据库
     * @param source   influxdb 客户端
     */
    public InfluxDbEngineDataSource(String name, String url, String username,
                                    String password, String database, InfluxDB source) {
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.database = database;
        this.source = source;
    }

    /** 获取名称 */
    @Override
    public String name() {
        return name;
    }

    /** 获取客户端 */
    @Override
    public InfluxDB getSource() {
        return source;
    }

    /** 设置客户端 */
    @Override
    public EngineDataSource<InfluxDB> setSource(Object source) {
        return this;
    }

    /** 非 SQL 方言返回 空 */
    @Override
    public Dialect getDialect() {
        return null;
    }

    /** 忽略方言设置 */
    @Override
    public EngineDataSource<InfluxDB> setDialect(Dialect dialect) {
        return this;
    }

    /** 连接地址 */
    @Override
    public String url() {
        return url;
    }

    /** 用户名 */
    @Override
    public String username() {
        return username;
    }

    /** 密码 */
    @Override
    public String password() {
        return password;
    }

    /** 数据库名 */
    @Override
    public String database() {
        return database;
    }

    /** 关闭客户端连接 */
    @Override
    public void close() {
        if (source != null) {
            source.close();
        }
    }
}
