// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import com.chua.opentelemetry.support.datasource.OpenTelemetryDataSource;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.Trace;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenTelemetry 引擎
 * <p>
 * 实现 {@link Engine} 接口, 以 SPI 方式注册为 {@code "opentelemetry"},
 * 提供多数据源管理与 Span/Trace 查询入口。
 * </p>
 *
 * <pre>{@code
 * // 通过 SPI 创建
 * OpenTelemetryEngine engine = (OpenTelemetryEngine) Engine.create("opentelemetry");
 *
 * // 或者使用自动装配的 Bean
 * OpenTelemetryClient client = engine.client();
 * List<Trace> traces = engine.traces();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi("opentelemetry")
public class OpenTelemetryEngine implements Engine {

    /**
     * 数据源映射
     */
    private final Map<String, EngineDataSource<OpenTelemetryClient>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名
     */
    private volatile String defaultDataSourceName;

    /**
     * 已注册的数据源数
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public OpenTelemetryEngine() {
        System.out.println("[DBG-ENGINE] instance " + System.identityHashCode(this) + " created");
    }

    /**
     * 注册数据源
     * <p>
     * 仅接受底层源为 {@link OpenTelemetryClient} 或客户端注入的 spring 地址字符串的数据源,
     * 其它类型显式拒绝, 避免注册出一个不可用的数据源。
     * </p>
     *
     * @param name       数据源名称
     * @param dataSource 数据源封装
     * @param <T>        底层类型
     * @return this
     */
    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        System.out.println("[DBG-ENGINE] addDataSource on " + System.identityHashCode(this) + " closed=" + closed.get());
        ensureOpen();
        requireName(name);
        if (dataSource == null) {
            throw new IllegalArgumentException("OpenTelemetry 数据源不能为空: " + name);
        }
        Object source = dataSource.getSource();
        OpenTelemetryDataSource target;
        if (source instanceof OpenTelemetryClient client) {
            target = new OpenTelemetryDataSource(name, client.getEndpoint(), client);
        } else if (source instanceof String url) {
            OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName(url).build();
            target = new OpenTelemetryDataSource(name, url, client);
        } else {
            throw new IllegalArgumentException("OpenTelemetryEngine 仅支持 OpenTelemetryClient 或 URL 字符串, 实际: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        register(name, target);
        return this;
    }

    /**
     * 便捷添加数据源(直接传客户端)
     *
     * @param name   数据源名称
     * @param client OpenTelemetry 客户端
     * @return this
     */
    public OpenTelemetryEngine addDataSource(String name, OpenTelemetryClient client) {
        ensureOpen();
        requireName(name);
        if (client == null) {
            throw new IllegalArgumentException("OpenTelemetry 客户端不能为空: " + name);
        }
        register(name, new OpenTelemetryDataSource(name, client.getEndpoint(), client));
        return this;
    }

    /**
     * 便捷添加数据源(传地址字符串, 使用数据源名称作为服务名)
     *
     * @param name    数据源名称
     * @param baseUrl 地址
     * @return this
     */
    public OpenTelemetryEngine addDataSource(String name, String baseUrl) {
        ensureOpen();
        requireName(name);
        OpenTelemetryClient client = OpenTelemetryClient.builder()
                .serviceName(name)
                .endpoint(baseUrl)
                .build();
        register(name, new OpenTelemetryDataSource(name, baseUrl, client));
        return this;
    }

    /**
     * 登记数据源, 同名替换时关闭被替换掉的数据源, 避免其客户端泄漏
     *
     * @param name       数据源名称
     * @param dataSource 数据源
     */
    private synchronized void register(String name, OpenTelemetryDataSource dataSource) {
        // 锁内复核: 与 close() 交错时若不再校验, 会向已清空的数据源表登记客户端, 使其永久泄漏
        ensureOpen();
        EngineDataSource<OpenTelemetryClient> previous = dataSources.put(name, dataSource);
        if (previous != null && previous != dataSource) {
            log.warn("[OpenTelemetry] 数据源 {} 被替换, 关闭原数据源", name);
            closeQuietly(previous);
        }
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
    }

    /**
     * 校验数据源名称
     *
     * @param name 数据源名称
     */
    private static void requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenTelemetry 数据源名称不能为空");
        }
    }

    /**
     * 关闭后调用守卫
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("OpenTelemetryEngine 已关闭, 不可再变更或获取数据源");
        }
    }

    /**
     * 获取默认客户端
     *
     * @return OpenTelemetryClient
     */
    public OpenTelemetryClient client() {
        ensureOpen();
        String name = defaultDataSourceName;
        if (name == null) {
            throw new IllegalStateException("OpenTelemetryEngine 尚未注册任何数据源");
        }
        return client(name);
    }

    /**
     * 获取指定数据源客户端
     *
     * @param name 数据源名称
     * @return OpenTelemetryClient
     */
    public OpenTelemetryClient client(String name) {
        ensureOpen();
        if (name == null) {
            return null;
        }
        EngineDataSource<OpenTelemetryClient> ds = dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("OpenTelemetry 数据源未找到: " + name + ", 已注册: " + dataSources.keySet());
        }
        OpenTelemetryClient client = ds.getSource();
        if (client == null) {
            throw new IllegalStateException("OpenTelemetry 数据源已关闭: " + name);
        }
        return client;
    }

    /**
     * 已注册的数据源名称
     *
     * @return 名称集合(快照)
     */
    public Set<String> dataSourceNames() {
        return Set.copyOf(dataSources.keySet());
    }

    /**
     * 查询全部 Trace
     *
     * @return Trace 列表
     */
    public List<Trace> traces() {
        return client().traces();
    }

    /**
     * 查询指定 Trace
     *
     * @param traceId traceId
     * @return Trace
     */
    public Trace trace(String traceId) {
        return client().trace(traceId);
    }

    /**
     * 查询全部 Span
     *
     * @return Span 列表
     */
    public List<Span> spans() {
        return client().spans();
    }

    /**
     * 按服务名查询 Span
     *
     * @param name Span 名称
     * @return Span 列表
     */
    public List<Span> spans(String name) {
        return client().spans(name);
    }

    /**
     * 按 Trace 查询 Span, 委托给 {@link #spansByTraceId(String)}
     *
     * @param traceId traceId
     * @return Span 列表
     */
    public List<Span> spansByTraceId(String traceId) {
        return client().spansByTraceId(traceId);
    }

    /**
     * 由外部地址创建数据源(自动装配内部使用)
     *
     * @param name   数据源名称
     * @param client OpenTelemetry 客户端
     * @return OpenTelemetryDataSource
     */

    /**
     * 存储数据
     *
     * @param name 存储名称
     * @param data 数据列表
     * @param <T>  数据类型
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出, OpenTelemetry 为只读数据源
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException("OpenTelemetry 引擎不支持数据存储");
    }

    /**
     * 设置默认数据源名称
     *
     * @param name 数据源名称
     * @return this
     */
    @Override
    public Engine setDefaultDataSourceName(String name) {
        ensureOpen();
        requireName(name);
        this.defaultDataSourceName = name;
        return this;
    }

    /**
     * 获取默认数据源名称
     *
     * @return 默认数据源名称, 未设置时为 null
     */
    @Override
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    /**
     * 获取 SQL 执行器
     * <p>按 {@link Engine#getExecutor(String)} 约定, 非 SQL 数据源返回 null,
     * 由调用方据此判定 {@link #supportsSql()}。</p>
     *
     * @param dataSourceName 数据源名称
     * @return 恒为 null
     * @deprecated 该引擎不支持 SQL 执行，请改用 {@link #supportsSql()} 判断能力
     */
    @Override
    @Deprecated
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    /**
     * 获取 SQL 执行器
     * <p>按 {@link Engine#getExecutor()} 约定, 非 SQL 数据源返回 null。</p>
     *
     * @return 恒为 null
     * @deprecated 该引擎不支持 SQL 执行，请改用 {@link #supportsSql()} 判断能力
     */
    @Override
    @Deprecated
    public SqlExecutor getExecutor() {
        return null;
    }

    /**
     * 执行数据操作语句。
     * <p>OpenTelemetry 为只读操作数据源，不支持数据操作语句。</p>
     *
     * @param ql     数据操作语句
     * @param params 参数
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出
     */
    @Override
    public int execute(String ql, Object... params) {
        throw new UnsupportedOperationException("OpenTelemetry 引擎为只读数据源，不支持数据操作语句");
    }

    /**
     * 判断是否支持元数据操作。
     *
     * @return OpenTelemetry 引擎不支持元数据操作，恒返回 false
     */
    @Override
    public boolean supportsMeta() {
        return false;
    }

    /**
     * 获取数据源
     *
     * @param name 名称
     * @param <T>  底层类型
     * @return 数据源, 未注册时为 null(由调用方判空)
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource(String name) {
        // 数据源表为 ConcurrentHashMap, 键为 null 时 get 会抛 NPE, 此处按契约返回 null
        if (name == null) {
            return null;
        }
        return (EngineDataSource<T>) dataSources.get(name);
    }

    /**
     * 获取默认数据源
     *
     * @param <T> 底层类型
     * @return 默认数据源, 未注册默认数据源(或引擎已关闭)时为 null(由调用方判空)
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource() {
        return getDataSource(defaultDataSourceName);
    }

    /**
     * 创建 Lambda 查询包装器
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出
     */
    @Override
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("OpenTelemetry 引擎不支持 Lambda 查询, 请使用 traces()/spans()");
    }

    /**
     * 创建 Lambda 更新包装器
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出
     */
    @Override
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("OpenTelemetry 引擎不支持 Lambda 更新");
    }

    /**
     * 创建 Lambda 删除包装器
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出
     */
    @Override
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("OpenTelemetry 引擎不支持 Lambda 删除");
    }

    /**
     * 获取方言
     * <p>按 {@link Engine#getDialect(String)} 约定, 非 SQL 数据源返回 null。</p>
     *
     * @param dataSourceName 数据源名称
     * @return 恒为 null
     */
    @Override
    public Dialect getDialect(String dataSourceName) {
        return null;
    }

    /**
     * 获取元数据入口
     * <p>与 {@link #supportsMeta()} 保持一致: 声明不支持即直接抛出,
     * 不再返回 null 让调用方踩到空指针。</p>
     *
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出
     */
    @Override
    public MetaData meta() {
        throw new UnsupportedOperationException("OpenTelemetry 引擎不支持元数据操作");
    }

    /**
     * 关闭引擎, 释放所有已注册数据源的底层客户端
     * <p>
     * 逐个关闭数据源而非仅清空映射: 客户端内部持有工作线程, 不释放即泄漏。
     * 与 {@code register} 共用监视器, 保证关闭后不会再有数据源登记进来。
     * 单个数据源关闭失败不影响其余数据源释放。方法幂等, 并发调用只有一次生效。
     * </p>
     */
    @Override
    public synchronized void close() {
        System.out.println("[DBG-ENGINE] instance " + System.identityHashCode(this) + " closed by " + Thread.currentThread().getName());
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<EngineDataSource<OpenTelemetryClient>> all = Wraps.list(dataSources.values());
        dataSources.clear();
        defaultDataSourceName = null;
        for (EngineDataSource<OpenTelemetryClient> ds : all) {
            closeQuietly(ds);
        }
    }

    /**
     * 是否已关闭
     *
     * @return 已关闭返回 true
     */
    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 关闭单个数据源, 失败仅记录日志
     * <p>关闭阶段必须保证其余数据源继续释放, 因此不上抛; 客户端本身幂等关闭。</p>
     *
     * @param dataSource 数据源
     */
    private static void closeQuietly(EngineDataSource<OpenTelemetryClient> dataSource) {
        try {
            dataSource.close();
        } catch (RuntimeException e) {
            log.warn("[OpenTelemetry] 数据源 {} 关闭失败", dataSource.name(), e);
        }
    }
}
