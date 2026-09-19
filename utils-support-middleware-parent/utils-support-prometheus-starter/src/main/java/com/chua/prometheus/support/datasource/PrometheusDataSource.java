package com.chua.prometheus.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.prometheus.support.client.PrometheusClient;

/**
 * Prometheus 数据源封装
 * <p>
 * 底层持有 {@link PrometheusClient}, 供 {@link com.chua.prometheus.support.engine.PrometheusEngine}
 * 按名称管理多个 Prometheus 实例。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PrometheusDataSource implements EngineDataSource<PrometheusClient> {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 底层客户端
     */
    private PrometheusClient client;

    /**
     * 连接 URL
     */
    private final String url;

    /**
     * 构造方法
     *
     * @param name   数据源名称
     * @param url    Prometheus 地址
     * @param client 客户端
     */
    public PrometheusDataSource(String name, String url, PrometheusClient client) {
        this.name = name;
        this.url = url;
        this.client = client;
    }

    @Override
    /** 名称 */
    public String name() {
        return name;
    }

    @Override
    /** 获取源 */
    public PrometheusClient getSource() {
        return client;
    }

    @Override
    /** 设置源 */
    public EngineDataSource<PrometheusClient> setSource(Object source) {
        if (source instanceof PrometheusClient pc) {
            this.client = pc;
        }
        return this;
    }

    @Override
    /** 获取Dialect */
    public Dialect getDialect() {
        return null;
    }

    @Override
    /** 设置Dialect */
    public EngineDataSource<PrometheusClient> setDialect(Dialect dialect) {
        return this;
    }

    @Override
    /** Url */
    public String url() {
        return url;
    }

    @Override
    /** 用户名 */
    public String username() {
        return null;
    }

    @Override
    /** 密码 */
    public String password() {
        return null;
    }
}
