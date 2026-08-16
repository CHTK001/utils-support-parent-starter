package com.chua.prometheus.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.prometheus.support.client.PrometheusClient;
import com.chua.prometheus.support.datasource.PrometheusDataSource;
import com.chua.prometheus.support.model.PrometheusAlert;
import com.chua.prometheus.support.model.PrometheusRule;
import com.chua.prometheus.support.model.PrometheusTarget;
import com.chua.prometheus.support.model.QueryResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus 引擎
 * <p>
 * 实现 {@link Engine} 接口, 以 SPI 方式注册为 {@code "prometheus"},
 * 提供多数据源管理与链式查询入口。Prometheus 为时序查询语义,
 * 不提供 JDBC 执行器与 Lambda 增删改。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("prometheus")
public class PrometheusEngine implements Engine {

    /**
     * 数据源映射
     */
    private final Map<String, EngineDataSource<PrometheusClient>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名
     */
    private String defaultDataSourceName;

    /**
     * 添加数据源
     *
     * @param name       数据源名称
     * @param dataSource 数据源封装
     * @param <T>        底层类型
     * @return this
     */
    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        Object source = dataSource.getSource();
        if (source instanceof PrometheusClient client) {
            dataSources.put(name, new PrometheusDataSource(name, dataSource.url(), client));
        } else if (source instanceof String url) {
            PrometheusClient client = PrometheusClient.builder().baseUrl(url).build();
            dataSources.put(name, new PrometheusDataSource(name, url, client));
        } else {
            throw new IllegalArgumentException("PrometheusEngine 仅支持 PrometheusClient 或 URL 字符串");
        }
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源(直接传客户端)
     *
     * @param name   数据源名称
     * @param client Prometheus 客户端
     * @return this
     */
    public PrometheusEngine addDataSource(String name, PrometheusClient client) {
        dataSources.put(name, new PrometheusDataSource(name, null, client));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源
     *
     * @param name    数据源名称
     * @param baseUrl Prometheus 地址
     * @return this
     */
    public PrometheusEngine addDataSource(String name, String baseUrl) {
        PrometheusClient client = PrometheusClient.builder().baseUrl(baseUrl).build();
        dataSources.put(name, new PrometheusDataSource(name, baseUrl, client));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源(带认证)
     *
     * @param name     数据源名称
     * @param baseUrl  Prometheus 地址
     * @param username 用户名
     * @param password 密码
     * @return this
     */
    public PrometheusEngine addDataSource(String name, String baseUrl, String username, String password) {
        PrometheusClient client = PrometheusClient.builder()
                .baseUrl(baseUrl)
                .basicAuth(username, password)
                .build();
        dataSources.put(name, new PrometheusDataSource(name, baseUrl, client));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 获取默认客户端
     *
     * @return PrometheusClient
     */
    public PrometheusClient client() {
        return client(defaultDataSourceName);
    }

    /**
     * 获取指定数据源客户端
     *
     * @param name 数据源名称
     * @return PrometheusClient
     */
    public PrometheusClient client(String name) {
        EngineDataSource<PrometheusClient> ds = dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("Prometheus 数据源未找到: " + name);
        }
        return ds.getSource();
    }

    /**
     * 即时查询
     *
     * @param promql PromQL
     * @return 查询结果
     */
    public QueryResult query(String promql) {
        return client().query(promql).execute();
    }

    /**
     * 查询抓取目标
     *
     * @return 目标列表
     */
    public List<PrometheusTarget> targets() {
        return client().targets();
    }

    /**
     * 查询规则
     *
     * @return 规则列表
     */
    public List<PrometheusRule> rules() {
        return client().rules();
    }

    /**
     * 查询告警
     *
     * @return 告警列表
     */
    public List<PrometheusAlert> alerts() {
        return client().alerts();
    }

    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException("Prometheus 引擎不支持数据存储");
    }

    @Override
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    @Override
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource(String name) {
        return (EngineDataSource<T>) dataSources.get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("Prometheus 引擎不支持 Lambda 查询, 请使用 query(promql)");
    }

    @Override
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("Prometheus 引擎不支持 Lambda 更新");
    }

    @Override
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("Prometheus 引擎不支持 Lambda 删除");
    }

    @Override
    public Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    public MetaData meta() {
        return null;
    }

    @Override
    public void close() {
        dataSources.clear();
    }
}