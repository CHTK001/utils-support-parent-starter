// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenTelemetry 数据源封装
 * <p>
 * 底层持有 {@link OpenTelemetryClient}，供 {@link com.chua.opentelemetry.support.engine.OpenTelemetryEngine}
 * 按名称管理多个客户端。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public class OpenTelemetryDataSource implements EngineDataSource<OpenTelemetryClient> {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 连接地址（本地查询模式无值）
     */
    private final String url;

    /**
     * 底层客户端, 关闭后置空
     */
    private volatile OpenTelemetryClient client;

    /**
     * 关闭标记, 保证 close 幂等
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造方法
     *
     * @param name   数据源名称
     * @param url    服务地址, 本地查询模式取 null
     * @param client 客户端
     */
    public OpenTelemetryDataSource(String name, String url, OpenTelemetryClient client) {
        if (client == null) {
            throw new IllegalArgumentException("OpenTelemetryDataSource[" + name + "] 的客户端不能为空");
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
    public OpenTelemetryClient getSource() {
        return client;
    }

    /**
     * 替换底层客户端
     *
     * @param source 新的客户端, 必须是 {@link OpenTelemetryClient}
     * @return this
     */
    @Override
    public EngineDataSource<OpenTelemetryClient> setSource(Object source) {
        if (closed.get()) {
            throw new IllegalStateException("OpenTelemetryDataSource[" + name + "] 已关闭, 不可再替换客户端");
        }
        if (!(source instanceof OpenTelemetryClient oec)) {
            throw new IllegalArgumentException("OpenTelemetryDataSource[" + name + "] 仅接受 OpenTelemetryClient, 实际: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        this.client = oec;
        return this;
    }

    /**
     * 获取方言
     * <p>
     * OpenTelemetry 为布尔/时间序列查询数据源, 不存在 SQL 方言, 按 {@link EngineDataSource#getDialect()}
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
    public EngineDataSource<OpenTelemetryClient> setDialect(Dialect dialect) {
        if (dialect != null) {
            throw new UnsupportedOperationException("OpenTelemetryDataSource[" + name + "] 为非 SQL 数据源, 不支持设置方言");
        }
        return this;
    }

    /**
     * 获取连接地址
     *
     * @return 服务地址, 本地查询模式为 null
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
     * 先委托 {@link EngineDataSource#close()} 默认实现释放底层 {@link OpenTelemetryClient}
     * (其 close 会释放 Ticker/工作线程), 再清空引用; 方法幂等, 并发调用只有一次生效。
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
