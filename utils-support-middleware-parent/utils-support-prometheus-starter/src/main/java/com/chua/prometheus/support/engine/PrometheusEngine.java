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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile String defaultDataSourceName;

    /**
     * 关闭标记, 用于关闭后调用守卫与幂等关闭
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 注册数据源
     * <p>
     * 仅接受底层源为 {@link PrometheusClient} 或 Prometheus 地址字符串的数据源,
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
        ensureOpen();
        requireName(name);
        if (dataSource == null) {
            throw new IllegalArgumentException("Prometheus 数据源不能为空: " + name);
        }
        Object source = dataSource.getSource();
        PrometheusDataSource target;
        if (source instanceof PrometheusClient client) {
            target = new PrometheusDataSource(name, dataSource.url(), client);
        } else if (source instanceof String url) {
            PrometheusClient client = PrometheusClient.builder().baseUrl(url).build();
            target = new PrometheusDataSource(name, url, client);
        } else {
            throw new IllegalArgumentException("PrometheusEngine 仅支持 PrometheusClient 或 URL 字符串, 实际: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        register(name, target);
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
        ensureOpen();
        requireName(name);
        if (client == null) {
            throw new IllegalArgumentException("Prometheus 客户端不能为空: " + name);
        }
        register(name, new PrometheusDataSource(name, client.baseUrl(), client));
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
        ensureOpen();
        requireName(name);
        PrometheusClient client = PrometheusClient.builder().baseUrl(baseUrl).build();
        register(name, new PrometheusDataSource(name, client.baseUrl(), client));
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
        ensureOpen();
        requireName(name);
        PrometheusClient client = PrometheusClient.builder()
                .baseUrl(baseUrl)
                .basicAuth(username, password)
                .build();
        register(name, new PrometheusDataSource(name, client.baseUrl(), client));
        return this;
    }

    /**
     * 登记数据源, 同名替换时关闭被替换掉的数据源, 避免其客户端泄漏
     *
     * @param name     数据源名称
     * @param dataSource 数据源
     */
    private synchronized void register(String name, PrometheusDataSource dataSource) {
        // 锁内复核: 与 close() 交错时若不再校验, 会向已清空的数据源表登记客户端, 使其永久泄漏
        ensureOpen();
        EngineDataSource<PrometheusClient> previous = dataSources.put(name, dataSource);
        if (previous != null && previous != dataSource) {
            log.warn("[Prometheus] 数据源 {} 被替换, 关闭原数据源", name);
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
            throw new IllegalArgumentException("Prometheus 数据源名称不能为空");
        }
    }

    /**
     * 关闭后调用守卫
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PrometheusEngine 已关闭, 不可再变更或获取数据源");
        }
    }

    /**
     * 获取默认客户端
     *
     * @return PrometheusClient
     */
    public PrometheusClient client() {
        ensureOpen();
        String name = defaultDataSourceName;
        if (name == null) {
            throw new IllegalStateException("PrometheusEngine 尚未注册任何数据源");
        }
        return client(name);
    }

    /**
     * 获取指定数据源客户端
     *
     * @param name 数据源名称
     * @return PrometheusClient
     */
    public PrometheusClient client(String name) {
        ensureOpen();
        EngineDataSource<PrometheusClient> ds = name == null ? null : dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("Prometheus 数据源未找到: " + name + ", 已注册: " + dataSources.keySet());
        }
        PrometheusClient client = ds.getSource();
        if (client == null) {
            throw new IllegalStateException("Prometheus 数据源已关闭: " + name);
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
     * 即时查询（Prometheus {@code /query} 语义）。
     * <p>不命名为 {@code query}：接口已声明 {@code query(String, Object...)} 并返回行列表，
     * 单参数调用会被静默解析到本方法而改变返回类型，故即时查询单独命名。</p>
     *
     * @param promql promql
     * @return 查询结果
     */
    public QueryResult queryInstant(String promql) {
        return client().query(promql).execute();
    }

    /**
     * 范围查询
     *
     * @param promql promql
     * @return 范围查询操作
     */
    public PrometheusClient.RangeQueryOperation queryRange(String promql) {
        return client().queryRange(promql);
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

    /**
     * 存储数据
     *
     * @param name 存储名称
     * @param data 数据列表
     * @param <T>  数据类型
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出, Prometheus 为只读数据源
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException("Prometheus 引擎不支持数据存储");
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
     */
    @Override
    @Deprecated
    public SqlExecutor getExecutor() {
        return null;
    }

    /**
     * 执行数据操作语句。
     * <p>Prometheus 为只读指标数据源，不支持数据操作语句；
     * 查询类需求请使用 {@link #queryInstant(String)} 执行 PromQL 即时查询。</p>
     *
     * @param ql     数据操作语句
     * @param params 参数
     * @return 永不返回
     * @throws UnsupportedOperationException 始终抛出
     */
    @Override
    public int execute(String ql, Object... params) {
        throw new UnsupportedOperationException("Prometheus 引擎为只读数据源，不支持数据操作语句，请使用 queryInstant(promql) 执行 PromQL 即时查询");
    }

    /**
     * 判断是否支持元数据操作。
     *
     * @return Prometheus 引擎不支持元数据操作，恒返回 false
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
        throw new UnsupportedOperationException("Prometheus 引擎不支持 Lambda 查询, 请使用 queryInstant(promql)");
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
        throw new UnsupportedOperationException("Prometheus 引擎不支持 Lambda 更新");
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
        throw new UnsupportedOperationException("Prometheus 引擎不支持 Lambda 删除");
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
        throw new UnsupportedOperationException("Prometheus 引擎不支持元数据操作");
    }

    /**
     * 关闭引擎, 释放所有已注册数据源的底层客户端
     * <p>
     * 逐个关闭数据源而非仅清空映射: JDK HttpClient 内部持有选择器线程, 不释放即泄漏。
     * 与 {@code register} 共用监视器, 保证关闭后不会再有数据源登记进来。
     * 单个数据源关闭失败不影响其余数据源释放。方法幂等, 并发调用只有一次生效。
     * </p>
     */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<EngineDataSource<PrometheusClient>> all = new ArrayList<>(dataSources.values());
        dataSources.clear();
        defaultDataSourceName = null;
        for (EngineDataSource<PrometheusClient> ds : all) {
            closeQuietly(ds);
        }
    }

    /**
     * 关闭单个数据源, 失败仅记录日志
     * <p>关闭阶段必须保证其余数据源继续释放, 因此不上抛; 客户端本身幂等关闭。</p>
     *
     * @param dataSource 数据源
     */
    private static void closeQuietly(EngineDataSource<PrometheusClient> dataSource) {
        try {
            dataSource.close();
        } catch (RuntimeException e) {
            log.warn("[Prometheus] 数据源 {} 关闭失败", dataSource.name(), e);
        }
    }
}
