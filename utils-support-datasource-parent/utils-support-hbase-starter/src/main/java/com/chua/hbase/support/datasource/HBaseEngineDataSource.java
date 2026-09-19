package com.chua.hbase.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.apache.hadoop.hbase.client.Connection;

/**
 * HBase 数据源封装，持有真实 {@link Connection}（ZooKeeper 寻址）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HBaseEngineDataSource implements EngineDataSource<Connection> {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * HBase 连接
     */
    private final Connection source;

    /**
     * 连接描述（quorum 串）
     */
    private final String url;

    /**
     * 构造数据源。
     *
     * @param name   数据源名称
     * @param url    连接描述，如 {@code 172.16.0.40:2181}
     * @param source HBase 连接
     */
    public HBaseEngineDataSource(String name, String url, Connection source) {
        this.name = name;
        this.url = url;
        this.source = source;
    }

    /**
     * 获取名称
    */
    @Override
    public String name() {
        return name;
    }

    /**
     * 获取连接
    */
    @Override
    public Connection getSource() {
        return source;
    }

    /**
     * 不支持运行期替换连接
    */
    @Override
    public EngineDataSource<Connection> setSource(Object source) {
        throw new UnsupportedOperationException("运行期不支持替换数据源对象，请重新调用 addDataSource 注册新数据源");
    }

    /**
     * 非 SQL 方言返回 空
    */
    @Override
    public Dialect getDialect() {
        return null;
    }

    /**
     * 忽略方言设置
    */
    @Override
    public EngineDataSource<Connection> setDialect(Dialect dialect) {
        return this;
    }

    /**
     * 连接描述
    */
    @Override
    public String url() {
        return url;
    }

    /**
     * 无用户名概念
    */
    @Override
    public String username() {
        return null;
    }

    /**
     * 无密码概念
    */
    @Override
    public String password() {
        return null;
    }
}
