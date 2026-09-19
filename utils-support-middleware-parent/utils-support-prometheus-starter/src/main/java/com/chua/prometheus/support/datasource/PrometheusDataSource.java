package com.chua.prometheus.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.prometheus.support.client.PrometheusClient;

import java.util.concurrent.atomic.AtomicBoolean;

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
     * 底层客户端, 关闭后置空
     */
    private volatile PrometheusClient client;

    /**
     * 连接 URL
     */
    private final String url;

    /**
     * 关闭标记, 保证 close 幂等
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造方法
     *
     * @param name   数据源名称
     * @param url    Prometheus 地址
     * @param client 客户端
     */
    public PrometheusDataSource(String name, String url, PrometheusClient client) {
        if (client == null) {
            throw new IllegalArgumentException("PrometheusDataSource[" + name + "] 的客户端不能为空");
        }
        this.name = name;
        this.url = url;
        this.client = client;
    }

    /**
     * 获取数据源名称
     *
     * @return 数据源名称
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 获取底层客户端
     *
     * @return 客户端, 已关闭时为 null
     */
    @Override
    public PrometheusClient getSource() {
        return client;
    }

    /**
     * 替换底层客户端
     *
     * @param source 新的客户端, 必须是 {@link PrometheusClient}
     * @return this
     */
    @Override
    public EngineDataSource<PrometheusClient> setSource(Object source) {
        if (closed.get()) {
            throw new IllegalStateException("PrometheusDataSource[" + name + "] 已关闭, 不可再替换客户端");
        }
        if (!(source instanceof PrometheusClient pc)) {
            throw new IllegalArgumentException("PrometheusDataSource[" + name + "] 仅接受 PrometheusClient, 实际: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        this.client = pc;
        return this;
    }

    /**
     * 获取方言
     * <p>
     * Prometheus 为时序 HTTP 查询数据源, 不存在 SQL 方言, 按 {@link EngineDataSource#getDialect()}
     * 约定(非 SQL 数据源返回 null)固定返回 null, 调用方需自行判空。
     * </p>
     *
     * @return 恒为 null
     */
    @Override
    public Dialect getDialect() {
        return null;
    }

    /**
     * 设置方言
     *
     * @param dialect 方言实例
     * @return this
     */
    @Override
    public EngineDataSource<PrometheusClient> setDialect(Dialect dialect) {
        if (dialect != null) {
            throw new UnsupportedOperationException("PrometheusDataSource[" + name + "] 为非 SQL 数据源, 不支持设置方言");
        }
        return this;
    }

    /**
     * 获取连接地址
     *
     * @return Prometheus 地址, 未知时为 null
     */
    @Override
    public String url() {
        return url;
    }

    /**
     * 获取用户名
     * <p>认证信息已封装在客户端内部, 此处不回显, 恒为 null。</p>
     *
     * @return 恒为 null
     */
    @Override
    public String username() {
        return null;
    }

    /**
     * 获取密码
     * <p>认证信息已封装在客户端内部, 此处不回显, 恒为 null。</p>
     *
     * @return 恒为 null
     */
    @Override
    public String password() {
        return null;
    }

    /**
     * 是否已关闭
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 关闭数据源
     * <p>
     * 先委托 {@link EngineDataSource#close()} 默认实现释放底层 {@link PrometheusClient}
     * (其 close 会释放 JDK HttpClient 的线程资源), 再清空引用; 方法幂等, 并发调用只有一次生效。
     * </p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            EngineDataSource.super.close();
        } finally {
            this.client = null;
        }
    }
}
