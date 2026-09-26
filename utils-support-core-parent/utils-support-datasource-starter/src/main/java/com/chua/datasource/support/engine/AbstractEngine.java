package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.ddl.DialectAware;
import com.chua.common.support.lang.datasource.engine.ddl.DslManager;
import com.chua.common.support.lang.datasource.engine.ddl.EngineAware;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.meta.DefaultMetaData;
import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserManager;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.wrapper.EngineDeleteWrapper;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import com.chua.datasource.support.wrapper.EngineUpdateWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 抽象引擎基类，提供默认的 Engine 接口实现。
 * <p>
 * 子类只需实现 {@link #executeNewQuery} 方法即可获得完整的 ORM 能力。
 * 更新/删除 操作默认基于内存 数据存储 执行，子类可重写
 * {@link #executeUpdate} 和 {@link #executeDelete} 实现真实数据库操作。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractEngine implements Engine {

    /**
     * 日志记录器
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AbstractEngine.class);

    /**
     * 内存数据存储映射表，键为表名，值为数据列表。
     */
    protected final Map<String, List<?>> dataStores = new ConcurrentHashMap<>();

    /**
     * 数据源映射表，存储所有注册的数据源。
     */
    protected final Map<String, EngineDataSource<Object>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称。
     */
    protected String defaultDataSourceName;

    /**
     * 引擎关闭标记，保证 {@link #close()} 幂等。
     */
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * DDL 管理器解析结果缓存，首次解析后持有（含已注入的上下文参数）。
     */
    private volatile DslManager dslManagerCache;

    /**
     * 引擎拦截器扩展缓存，首次访问时通过 SPI 加载。
     */
    private volatile List<EngineInterceptor> interceptorCache;

    @Override
    /**
     * 获取引擎拦截器扩展列表（首次调用后缓存）。
     * <p>通过 SPI 查找 {@code engine-interceptor} 扩展点实现，
     * 结果按 order 降序排列；无注册实现时返回空列表。
     * <p>本方法在每条语句的 before/after/error 三个环节都会被调用，
     * 因此结果必须缓存，否则每次执行都要重扫 SPI 注册表。</p>
     *
     * @return 拦截器列表（非 null，只读）
     */
    public List<EngineInterceptor> interceptors() {
        if (interceptorCache == null) {
            synchronized (this) {
                if (interceptorCache == null) {
                    List<EngineInterceptor> loaded = ServiceProvider.of(EngineInterceptor.class)
                            .getNewExtensions(EngineInterceptor.SPI_NAME, this);
                    interceptorCache = loaded == null ? List.of() : List.copyOf(loaded);
                }
            }
        }
        return interceptorCache;
    }

    @Override
    /**
     * 查询
    */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new EngineQueryWrapper<>(this, entityClass);
    }

    @Override
    /**
     * 更新
    */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new EngineUpdateWrapper<>(this, entityClass);
    }

    @Override
    /**
     * 删除
    */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new EngineDeleteWrapper<>(this, entityClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 添加数据源
     *
     * @param name 名称
     * @param ds ds
     * @return 添加数据源的结果
     */
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        ensureOpen("添加数据源 " + name);
        dataSources.put(name, (EngineDataSource<Object>) ds);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    @Override
    /**
     * 设置默认数据源名称
    */
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    /**
     * 存储
    */
    public <T> Engine store(String name, List<T> data) {
        ensureOpen("写入内存表 " + name);
        dataStores.put(name, new ArrayList<>(data));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 断言引擎仍处于可用状态。
     *
     * @param action 触发操作描述，用于异常定位
     * @throws IllegalStateException 引擎已关闭时抛出
     */
    protected void ensureOpen(String action) {
        if (closed.get()) {
            throw new IllegalStateException("引擎已关闭，无法执行" + action + ": " + getClass().getSimpleName());
        }
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor(String n) {
        return null;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取数据源
     * <p>名称为 null 时返回 null：底层为 {@link ConcurrentHashMap}，
     * 以 null 键查询会抛 NPE，而"未设置默认数据源"是正常状态，不该让无参调用崩溃。</p>
     *
     * @param n 数据源名称
     * @return 数据源封装实例，不存在时返回 null
     */
    public <T> EngineDataSource<T> getDataSource(String n) {
        if (n == null) {
            return null;
        }
        return (EngineDataSource<T>) dataSources.get(n);
    }

    @Override
    /**
     * 获取默认数据源
     *
     * @return 获取数据源的结果
     */
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    /**
     * 列出全部已注册的数据源名称
     *
     * @return 数据源名称集合（不可变）
     */
    public Set<String> dataSourceNames() {
        return Set.copyOf(dataSources.keySet());
    }

    @Override
    /**
     * 摘除并关闭指定数据源
     * <p>被摘除的若是默认数据源，默认数据源名同时置空，避免后续无参调用指向已释放资源。
     * 关闭失败只记录日志并继续摘除：调用方的意图是"移除"，不能因底层 close 异常而留下悬挂引用。</p>
     *
     * @param name 数据源名称
     * @return this
     */
    public Engine removeDataSource(String name) {
        if (name == null) {
            return this;
        }
        EngineDataSource<Object> removed = dataSources.remove(name);
        if (removed == null) {
            return this;
        }
        if (name.equals(defaultDataSourceName)) {
            defaultDataSourceName = null;
        }
        try {
            removed.close();
        } catch (Exception e) {
            // 摘除必须生效，关闭异常只留证据
            log.warn("数据源关闭失败 name={}: {}", name, e.getMessage(), e);
        }
        return this;
    }

    @Override
    /**
     * 获取Dialect
     * <p>数据源不存在、名称为 null 或未设置方言时返回 null。</p>
     *
     * @param n 数据源名称
     * @return 方言实例，无匹配时返回 null
     */
    public Dialect getDialect(String n) {
        if (n == null) {
            return null;
        }
        EngineDataSource<?> ds = dataSources.get(n);
        return ds != null ? ds.getDialect() : null;
    }

    @Override
    /**
     * 获取默认数据源名称
    */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    /**
     * Meta
    */
    public com.chua.common.support.lang.datasource.meta.MetaData meta() {
        return new DefaultMetaData(this);
    }

    @Override
    /**
     * 是否支持元数据操作：以 meta() 是否返回真实实现为准。
     * 默认实现 {@link DefaultMetaData} 各方法均抛异常，此时报告不支持；
     * 子类覆盖 meta() 返回真实元数据实现后自动报告支持。
     */
    public boolean supportsMeta() {
        return !(meta() instanceof DefaultMetaData);
    }

    @Override
    /**
     * 是否已关闭
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    /**
     * 关闭引擎，释放所有已注册数据源的底层资源。
     *
     * <p>遍历所有 EngineDataSource 逐一关闭，再清理内存数据与数据源映射。
     * 整体幂等：重复调用直接返回，不会二次释放；关闭过程中单个数据源失败
     * 只记录日志，不阻断其余数据源的释放。</p>
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Map.Entry<String, EngineDataSource<Object>> entry : dataSources.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                // 单个数据源关闭失败不阻断其余关闭，但必须留下证据
                log.warn("数据源关闭失败 name={}: {}", entry.getKey(), e.getMessage(), e);
            }
        }
        dataStores.clear();
        dataSources.clear();
        defaultDataSourceName = null;
    }

    @Override
    /**
     * 设置Tunnel
    */
    public Engine setTunnel(String dataSourceName, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        EngineDataSource<?> ds = dataSources.get(dataSourceName);
        if (ds != null) {
            ((EngineDataSource<Object>) ds).setTunnelPort(tunnel.open());
        }
        return this;
    }

    @Override
    /**
     * 打开Tunnel
    */
    public int openTunnel(String dataSourceName, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        EngineDataSource<?> ds = dataSources.get(dataSourceName);
        if (ds != null) {
            int port = tunnel.open();
            ((EngineDataSource<Object>) ds).setTunnelPort(port);
            return port;
        }
        return -1;
    }

    @Override
    /**
     * 关闭Tunnel
    */
    public Engine closeTunnel(String dataSourceName) {
        EngineDataSource<?> ds = dataSources.get(dataSourceName);
        if (ds != null && ds.tunnelPort() > 0) {
            // 注意：此处仅重置端口标记，实际隧道关闭由持有者负责
            ((EngineDataSource<Object>) ds).setTunnelPort(0);
        }
        return this;
    }

    /**
     * 创建新版查询包装器。
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询包装器
     */
    public <T> EngineQueryWrapper<T> queryNew(Class<T> entityClass) {
        return new EngineQueryWrapper<>(this, entityClass);
    }

    /**
     * 执行新版查询。
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 查询结果
     */
    public <T> List<T> execute(EngineQueryWrapper<T> wrapper) {
        return executeQuery(wrapper, wrapper.getEntityClass());
    }

    /**
     * 分页执行新版查询。
     *
     * @param wrapper 查询包装器
     * @param pn      页码
     * @param ps      每页大小
     * @param <T>     实体类型
     * @return 分页结果
     */
    public <T> Page<T> executePage(EngineQueryWrapper<T> wrapper, int pn, int ps) {
        return executePage(wrapper, wrapper.getEntityClass(), pn, ps);
    }

    /**
     * 执行旧版查询。
     * <p>执行前后依次回调 {@link EngineInterceptor} 扩展的
     * {@code beforeQuery / afterQuery / onError}。</p>
     *
     * @param wrapper     查询包装器
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询结果
     */
    public <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper, Class<T> entityClass) {
        var sql = wrapper.buildSql();
        String ql = sql.whereClause();
        Object[] queryParams = sql.params().toArray();
        return interceptQuery(ql, queryParams, () -> executeQueryFull(sql));
    }

    /**
     * 执行完整查询 SQL 信息（含 SELECT 列、WHERE、GROUP BY、ORDER BY、LIMIT/OFFSET）。
     * <p>
     * 默认实现把 WHERE 条件委托给子类的 {@link #executeNewQuery}（分页参数固定传 0，
     * 即不做引擎侧截断），再由 {@link #processQueryResult} 统一完成排序与
     * limit/offset 单次截取，适用于内存/文件/NoSQL 引擎。
     * SQL 引擎（如 {@link JdbcEngine}）应覆盖本方法，将 SELECT 列、GROUP BY、ORDER BY、
     * LIMIT/OFFSET 全部下推到数据库执行，避免全表数据加载到 JVM。
     * </p>
     *
     * @param sql 查询 SQL 信息
     * @param <T> 实体类型
     * @return 查询结果
     */
    protected <T> List<T> executeQueryFull(QuerySql<T> sql) {
        // 分页只在 processQueryResult 截一次：这里以 (0,0) 取全量过滤结果，
        // 避免 executeNewQuery 先截、排序后内存再截导致第二页起恒为空
        List<T> result = executeNewQuery(sql.whereClause(), sql.params().toArray(),
                sql.entityClass(), 0, 0);
        return processQueryResult(sql, result);
    }

    /**
     * 对查询结果执行内存后处理：物理分页截断、空元素过滤与排序。
     *
     * @param sql    查询 SQL 信息
     * @param result 原始查询结果
     * @param <T>    实体类型
     * @return 后处理后的查询结果
     */
    private <T> List<T> processQueryResult(QuerySql<T> sql, List<T> result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
 // 过滤 空 元素，避免排序引发 NPE
        List<T> valid = result.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
 // 先全量排序，再按 limit/offset 截取当前页
        if (sql.orderBys() != null && !sql.orderBys().isEmpty() && !valid.isEmpty()) {
            List<T> sorted = new ArrayList<>(valid);
            sorted.sort((a, b) -> compareOrdered(a, b, sql.orderBys()));
            valid = sorted;
        }
 // 如果设置了 限制 但 dialect 不支持物理分页，内存截取
        if (sql.hasLimit()) {
            int from = sql.offset();
            if (from >= valid.size()) {
                return Collections.emptyList();
            }
            int to = Math.min(from + sql.limit(), valid.size());
            // 返回独立副本，避免 subList 试图图泄漏内存存储
            return new ArrayList<>(valid.subList(from, to));
        }
        return valid;
    }

    /**
     * 按 订单 BY 列表比较两个对象。
     *
     * @param a        对象 A
     * @param b        对象 B
     * @param orderBys 排序字段列表，格式为 "字段名称 ASC" 或 "字段名称 DESC"
     * @param <T>      对象类型
     * @return 比较结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> int compareOrdered(T a, T b, List<String> orderBys) {
        for (String ob : orderBys) {
            String[] parts = ob.trim().split("\\s+");
            String field = parts[0];
            boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
            Object va = getPropertyValue(a, field);
            Object vb = getPropertyValue(b, field);
            int cmp;
            if (va == null && vb == null) {
                cmp = 0;
            } else if (va == null) {
                cmp = -1;
            } else if (vb == null) {
                cmp = 1;
            } else if (va instanceof Comparable && vb instanceof Comparable) {
                cmp = ((Comparable) va).compareTo(vb);
            } else {
                cmp = va.toString().compareTo(vb.toString());
            }
            if (cmp != 0) {
                return desc ? -cmp : cmp;
            }
        }
        return 0;
    }

    /**
     * 通过反射获取对象属性值。
     *
     * @param bean  对象实例
     * @param field 字段名
     * @return 属性值，获取失败返回 空
     */
    private static Object getPropertyValue(Object bean, String field) {
        return MethodCache.getValue(bean, field);
    }

    /**
     * 分页执行旧版查询。
     *
     * @param wrapper 查询包装器
     * @param ec      实体类类型
     * @param pn      页码
     * @param ps      每页大小
     * @param <T>     实体类型
     * @return 分页结果
     */
    public <T> Page<T> executePage(LambdaQueryWrapper<T> wrapper, Class<T> ec, int pn, int ps) {
        if (pn < 1) {
            pn = 1;
        }
        if (ps < 1) {
            throw new IllegalArgumentException("每页条数必须大于 0");
        }
        // SQL 引擎走数据库物理分页：COUNT 取总数 + 分页 SQL 取当前页，避免全表加载
        if (supportsNativePaging(ec)) {
            long total = executeCount(wrapper);
            int origLimit = wrapper.getLimit();
            int origOffset = wrapper.getOffset();
            List<T> records;
            try {
                // 临时注入分页参数，查询完成后恢复，避免污染调用方 wrapper 复用
                wrapper.limit(ps).offset((pn - 1) * ps);
                records = executeQuery(wrapper, ec);
            } finally {
                wrapper.limit(origLimit).offset(origOffset);
            }
            return new Page<>(pn, ps, total, records);
        }
        List<T> all = executeQuery(wrapper, ec);
        int from = (pn - 1) * ps;
        int to = Math.min(from + ps, all.size());
        if (from >= all.size()) {
            return new Page<>(pn, ps, all.size(), Collections.emptyList());
        }
        // 独立副本，避免 subList 视图持有内存存储引用
        return new Page<>(pn, ps, all.size(), new ArrayList<>(all.subList(from, to)));
    }

    /**
     * 当前引擎是否支持数据库物理分页（COUNT + 分页 SQL）。
     * <p>默认返回 false，走全量加载后内存分页；SQL 引擎覆盖返回 true。</p>
     *
     * @param entityClass 实体类类型（内存存储中存在该实体数据时应回退内存分页）
     * @return true 表示支持物理分页
     */
    protected boolean supportsNativePaging(Class<?> entityClass) {
        return false;
    }

    /**
     * 统计查询条件命中的总行数（供 {@code LambdaQueryWrapper#count()} 终端方法调用）。
     * <p>SQL 引擎优先下推 {@code SELECT COUNT(*)} 物理计数；
     * 内存引擎回退为执行查询后对结果计数。</p>
     *
     * @param wrapper 查询包装器（不含分页参数）
     * @param <T>     实体类型
     * @return 总行数
     */
    public <T> long queryCount(LambdaQueryWrapper<T> wrapper) {
        if (supportsNativePaging(wrapper.getEntityClass())) {
            return executeCount(wrapper);
        }
        return executeQuery(wrapper, wrapper.getEntityClass()).size();
    }

    /**
     * 统计查询条件命中的总行数，用于物理分页的 total。
     * <p>仅在 {@link #supportsNativePaging(Class)} 返回 true 时被调用，
     * 由 SQL 引擎覆盖实现，通常渲染为 {@code SELECT COUNT(*) FROM (...原始查询...) }。</p>
     *
     * @param wrapper 查询包装器（不含分页参数）
     * @param <T>     实体类型
     * @return 总行数
     */
    protected <T> long executeCount(LambdaQueryWrapper<T> wrapper) {
        throw new UnsupportedOperationException("当前引擎不支持物理分页计数");
    }

    /**
     * 执行基于 WHERE 条件的查询。
     *
     * @param where       WHERE 子句
     * @param params      参数值数组
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询结果
     */
    protected abstract <T> List<T> executeNewQuery(
            String where, Object[] params, Class<T> entityClass, int limit, int offset);

    /**
     * 执行更新操作。
     * <p>默认调用内存实现，子类可重写。执行前后依次回调
     * {@link EngineInterceptor} 扩展的 {@code beforeUpdate / afterUpdate / onError}。</p>
     *
     * @param sql  更新 SQL 信息
     * @param <T>  实体类型
     * @return 影响行数
     */
    public <T> int executeUpdate(UpdateSql<T> sql) {
        String ql = sql.whereClause();
        Object[] params = sql.params() == null ? new Object[0] : sql.params().toArray();
        return interceptUpdate(ql, params, () -> executeUpdateInMemory(sql));
    }

    /**
     * 执行删除操作。
     * <p>默认调用内存实现，子类可重写。执行前后依次回调
     * {@link EngineInterceptor} 扩展的 {@code beforeUpdate / afterUpdate / onError}。</p>
     *
     * @param sql  删除 SQL 信息
     * @param <T>  实体类型
     * @return 影响行数
     */
    public <T> int executeDelete(DeleteSql<T> sql) {
        String ql = sql.whereClause();
        Object[] params = sql.params() == null ? new Object[0] : sql.params().toArray();
        return interceptUpdate(ql, params, () -> executeDeleteInMemory(sql));
    }

    @SuppressWarnings("unchecked")
    /**
     * 执行更新入内存
     *
     * @param sql SQL
     * @return 执行更新入内存的结果
     */
    private <T> int executeUpdateInMemory(UpdateSql<T> sql) {
        List<T> data = getData(sql.entityClass());
        if (data.isEmpty()) {
            return 0;
        }
        String where = sql.whereClause();
        String setClause = sql.setClause();
        if (setClause == null || setClause.isEmpty()) {
            return 0;
        }

 // 解析 设置 子句，分离参数：仅 "?" 占位符消费参数，字面量赋值直接取值
        List<Object> params = sql.params() == null ? Collections.emptyList() : sql.params();
        Map<String, Object> setValues = new LinkedHashMap<>();
        String[] setParts = setClause.split(", ");
        int paramIdx = 0;
        for (String setPart : setParts) {
            int eqIdx = setPart.indexOf(" = ");
            if (eqIdx <= 0) {
                continue;
            }
            String column = setPart.substring(0, eqIdx).trim();
            String rhs = setPart.substring(eqIdx + 3).trim();
            if ("?".equals(rhs)) {
                if (paramIdx >= params.size()) {
                    throw new IllegalStateException("UPDATE SET 占位符数量超过参数个数: " + setClause);
                }
                setValues.put(column, params.get(paramIdx++));
            } else {
                Object literal = parseMemoryLiteral(rhs);
                if (literal != null) {
                    setValues.put(column, literal);
                }
                // 无法求值的 SQL 表达式（如 now()）在内存引擎跳过，不消费参数
            }
        }

 // WHERE 参数在 设置 参数之后（按实际消费的占位符个数切分）
        int setParamCount = paramIdx;
        List<Object> whereParams;
        int totalParams = params.size();
        if (totalParams > setParamCount) {
            whereParams = params.subList(setParamCount, totalParams);
        } else {
            whereParams = Collections.emptyList();
        }

        // 过滤并更新
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> whereParamList = new ArrayList<>(whereParams);
        var predicate = parser.parse(where, whereParamList);
        List<T> updated = data.stream().filter(predicate).toList();
        for (T item : updated) {
            for (Map.Entry<String, Object> entry : setValues.entrySet()) {
                setFieldValue(item, entry.getKey(), entry.getValue());
            }
        }
        return updated.size();
    }

    @SuppressWarnings("unchecked")
    /**
     * 执行删除入内存
     *
     * @param sql SQL
     * @return 执行删除入内存的结果
     */
    private <T> int executeDeleteInMemory(DeleteSql<T> sql) {
        List<T> data = getData(sql.entityClass());
        if (data.isEmpty()) {
            return 0;
        }
        String where = sql.whereClause();
        if (where == null || where.isEmpty()) {
            return 0;
        }

        List<Object> params = sql.params();
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = (params != null) ? new ArrayList<>(params) : new ArrayList<>();
        var predicate = parser.parse(where, paramList);
        int before = data.size();
        List<T> remaining = data.stream().filter(predicate.negate()).toList();
        int removed = before - remaining.size();
        if (removed > 0) {
            List<T> next = new ArrayList<>(remaining);
            for (Map.Entry<String, List<?>> entry : dataStores.entrySet()) {
                if (entry.getValue() == data) {
                    dataStores.put(entry.getKey(), next);
                }
            }
        }
        return removed;
    }

    /**
     * 解析内存引擎可求值的 SQL 字面量。
     * <p>支持单引号字符串（'' 转义）、整数与小数；其余（NULL、函数表达式等）
     * 返回 null 表示无法在内存中求值，由调用方决定跳过。</p>
     *
     * @param rhs 赋值右侧文本
     * @return 字面量值，无法求值返回 null
     */
    private static Object parseMemoryLiteral(String rhs) {
        if (rhs == null || rhs.isEmpty()) {
            return null;
        }
        if (rhs.length() >= 2 && rhs.charAt(0) == '\'' && rhs.charAt(rhs.length() - 1) == '\'') {
            return rhs.substring(1, rhs.length() - 1).replace("''", "'");
        }
        try {
            if (rhs.indexOf('.') >= 0) {
                return Double.parseDouble(rhs);
            }
            return Long.parseLong(rhs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 为对象的字段设置值（基于反射）。
     *
     * @param obj   目标对象
     * @param field 字段名
     * @param value 字段值
     */
    private void setFieldValue(Object obj, String field, Object value) {
        MethodCache.setValue(obj, field, value);
    }

    /**
     * 获取指定实体类对应的数据列表。
     * <p>仅匹配实体表名与显式的 "default" 存储；无对应内存存储时返回空列表。
     * 不再回退到"随机取第一个存储"，避免跨表读到无关数据。</p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 数据列表
     */
    @SuppressWarnings("unchecked")
    protected <T> List<T> getData(Class<T> entityClass) {
        String tableName = getTableName(entityClass);
        List<?> data = dataStores.get(tableName);
        if (data == null) {
            data = dataStores.get("default");
        }
        if (data == null) {
            return Collections.emptyList();
        }
        return (List<T>) data;
    }

    /**
     * 将实体类名称转为表名（驼峰转下划线）。
     * <p>实体类标注 {@link TableName} 时优先使用注解值。</p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 表名
     */
    protected <T> String getTableName(Class<T> entityClass) {
        return resolveTableName(entityClass);
    }

    /**
     * 解析实体类对应的表名。
     * <p>优先读取 {@link TableName} 注解；未标注时将驼峰命名
     * 转换为下划线命名（如 my用户 → my_用户）。</p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 表名
     */
    public static <T> String resolveTableName(Class<T> entityClass) {
        TableName annotation = entityClass.getAnnotation(TableName.class);
        if (annotation != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        String simpleName = entityClass.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (char c : simpleName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /* ==================== 插入（渲染 INSERT → 交由引擎语句通道执行） ==================== */

    @Override
    /**
     * 插入单个实体
     *
     * @param entityClass 实体类类型
     * @param entity 待插入实体
     * @param <T> 实体类型
     * @return 受影响行数
     */
    public <T> int insert(Class<T> entityClass, T entity) {
        if (entityClass == null) {
            throw new IllegalArgumentException("实体类不能为空");
        }
        if (entity == null) {
            throw new IllegalArgumentException("待插入实体不能为空");
        }
        ensureOpen("插入 " + getTableName(entityClass));
        if (useMemoryChannel(entityClass)) {
            mutableMemoryRows(entityClass).add(entity);
            afterMemoryInsert(entityClass, 1);
            return 1;
        }
        return executeInsert(entityClass, List.of(entityRow(entity)));
    }

    @Override
    /**
     * 批量插入实体
     * <p>有 JDBC 执行器的引擎渲染为单条多行
     * {@code INSERT INTO t (...) VALUES (?, ?), (?, ?)}，一次网络往返完成；
     * 无执行器的引擎直接追加实体实例到内存表。</p>
     *
     * @param entityClass 实体类类型
     * @param entities 待插入实体列表
     * @param <T> 实体类型
     * @return 受影响行数合计
     */
    public <T> int insertBatch(Class<T> entityClass, List<T> entities) {
        if (entityClass == null) {
            throw new IllegalArgumentException("实体类不能为空");
        }
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        ensureOpen("批量插入 " + getTableName(entityClass));
        List<Map<String, Object>> rows = new ArrayList<>(entities.size());
        for (T entity : entities) {
            if (entity == null) {
                throw new IllegalArgumentException("待插入实体列表中存在 null 元素");
            }
            rows.add(entityRow(entity));
        }
        if (useMemoryChannel(entityClass)) {
            List<Object> target = mutableMemoryRows(entityClass);
            for (T entity : entities) {
                target.add(entity);
            }
            afterMemoryInsert(entityClass, entities.size());
            return entities.size();
        }
        return executeInsert(entityClass, rows);
    }

    /**
     * 判断本次插入是否走内存通道（直接追加实体实例）而非渲染 INSERT 语句。
     *
     * <p>判定规则：有 JDBC 执行器时一律走 SQL，交由数据库落库；
     * 无执行器（内存/文件引擎）时看目标表既有行的类型——
     * 空表或已存实体对象才允许直接追加实体实例；
     * 既有行是映射（常见于先 {@code store} 映射再原生 INSERT）时必须走 SQL 通道，
     * 否则同一张表会混有两种行类型，Lambda 条件只能匹配到其中一种、静默漏行。</p>
     *
     * @param entityClass 实体类类型
     * @return true 表示走内存通道
     */
    private boolean useMemoryChannel(Class<?> entityClass) {
        if (getExecutor() != null) {
            return false;
        }
        List<?> rows = dataStores.get(getTableName(entityClass));
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        Object first = rows.getFirst();
        return first != null && !(first instanceof Map);
    }

    /**
     * 获取实体对应内存表的可变行引用，表不存在时按实体表名挂载空表。
     *
     * @param entityClass 实体类类型
     * @return 可变行引用列表
     */
    @SuppressWarnings("unchecked")
    protected List<Object> mutableMemoryRows(Class<?> entityClass) {
        String table = getTableName(entityClass);
        return (List<Object>) dataStores.computeIfAbsent(table, k -> new ArrayList<>());
    }

    /**
     * 内存通道插入完成后的收尾钩子，默认无操作。
     * <p>文件引擎据此按 autopersist 配置把新增数据写回源文件。</p>
     *
     * @param entityClass 实体类类型
     * @param affected    本次插入行数
     */
    protected void afterMemoryInsert(Class<?> entityClass, int affected) {
    }

    /**
     * 把实体摊平为 列名 → 列值 的有序映射，列序按声明顺序（父类字段在前）。
     *
     * @param entity 实体实例
     * @return 列值映射（不可为 null，按声明顺序排列）
     */
    private Map<String, Object> entityRow(Object entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (java.lang.reflect.Field field : ReflectUtils.getPersistentFields(entity.getClass())) {
            row.put(SqlName.checkSimple(field.getName(), "列名"), MethodCache.getValue(entity, field.getName()));
        }
        if (row.isEmpty()) {
            throw new IllegalArgumentException("实体无可持久化字段: " + entity.getClass().getName());
        }
        return row;
    }

    /**
     * 渲染并执行多行 INSERT 语句。
     * <p>列清单取所有行键的并集（首行列序优先，后续行新增列追加），
     * 保证多行 {@code VALUES} 元数一致；值全部以 {@code ?} 占位符绑定，不做字面量内联。
     * 表名与列名均经 {@link SqlName} 白名单校验后才参与拼接。</p>
     *
     * @param entityClass 实体类类型，用于解析目标表名
     * @param rows 行数据（列名 → 列值），至少一行
     * @return 受影响行数
     */
    private int executeInsert(Class<?> entityClass, List<Map<String, Object>> rows) {
        String table = SqlName.check(resolveTableName(entityClass), "表名");
        List<String> columns = new ArrayList<>(rows.getFirst().keySet());
        for (Map<String, Object> row : rows) {
            for (String column : row.keySet()) {
                if (!columns.contains(column)) {
                    columns.add(column);
                }
            }
        }

        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>(rows.size() * columns.size());
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                values.append(", ");
            }
            values.append('(');
            for (int j = 0; j < columns.size(); j++) {
                if (j > 0) {
                    values.append(", ");
                }
                values.append('?');
                params.add(rows.get(i).get(columns.get(j)));
            }
            values.append(')');
        }
        return executeSql("INSERT INTO " + table + " (" + String.join(", ", columns)
                + ") VALUES " + values, params.toArray());
    }

    /* ==================== 能力入口（与 meta() 同模式） ==================== */

    /**
     * 获取 DDL 管理器入口（与 meta() 同模式）。
     *
     * <p>通过 SPI 解析，调用方无需手工 {@code new} 实现类：</p>
     * <ol>
     *   <li>取默认数据源方言协议名作为扩展键（如 {@code mysql}）查找</li>
     *   <li>未命中时回退到通用别名 {@link DslManager#DEFAULT_ALIAS}</li>
     * </ol>
     * <p>解析成功后自动注入上下文参数：实现 {@link com.chua.datasource.support.user.DataSourceAware}
     * 的注入 JDBC {@code DataSource}，实现 {@link com.chua.common.support.lang.datasource.engine.ddl.DialectAware}
     * 的注入当前方言。解析结果按引擎实例缓存，避免每次调用都重扫 SPI 注册表。</p>
     *
     * @return DDL 管理器实例
     * @throws UnsupportedOperationException 未注册任何 {@link DslManager} SPI 实现时抛出
     */
    @Override
    public DslManager ddl() {
        DslManager manager = dslManager();
        if (manager == null) {
            throw new UnsupportedOperationException("当前引擎不支持 DDL 管理：未注册 DslManager SPI 实现（扩展名 "
                    + DslManager.SPI_NAME + "），请注册方言专属实现或通用实现 " + DslManager.DEFAULT_ALIAS);
        }
        return manager;
    }

    /**
     * 解析并缓存 DDL 管理器，完成上下文参数注入。
     *
     * <p>解析结果可用性判定：依赖 JDBC 数据源的实现（{@link DataSourceAware}）
     * 在本引擎没有 JDBC 数据源时视为不可用，继续走兜底别名，
     * 最终仍无命中则由 {@link #ddl()} 报告"不支持 DDL 管理"——
     * 这比让调用方拿到一个必然抛异常的管理器更准确。</p>
     *
     * @return DDL 管理器实例，无可用实现时返回 null
     */
    protected DslManager dslManager() {
        DslManager cached = dslManagerCache;
        if (cached != null) {
            return cached;
        }
        DslManager resolved = null;
        for (String key : ddlSpiKeys()) {
            // 不传构造参数：上下文一律由 EngineAware / DataSourceAware / DialectAware 注入。
            // 传参会触发 SPI 按参数个数选构造器，实现类一旦有多个构造器就可能选错签名。
            resolved = ServiceProvider.of(DslManager.class).getNewExtension(key);
            if (resolved != null) {
                break;
            }
        }
        if (resolved != null) {
            javax.sql.DataSource dataSource = resolveJdbcDataSource();
            if (resolved instanceof DataSourceAware && dataSource == null) {
                // 该实现需要 JDBC 数据源，本引擎没有：视为不支持，不缓存
                return null;
            }
            injectDdlContext(resolved, dataSource);
            dslManagerCache = resolved;
        }
        return resolved;
    }

    /**
     * DDL 管理器的 SPI 查找键，按顺序尝试。
     *
     * <p>默认顺序：方言协议名（JDBC 类引擎）→ {@link DslManager#DEFAULT_ALIAS}。
     * 非 JDBC 引擎没有方言协议，应覆盖本方法返回自身专属别名，
     * 避免落进依赖 {@code DataSource} 的通用实现。</p>
     *
     * @return SPI 查找键序列（非 null，不含重复项）
     */
    protected List<String> ddlSpiKeys() {
        String protocol = ddlDialectProtocol();
        List<String> keys = new ArrayList<>(2);
        if (protocol != null) {
            keys.add(protocol);
        }
        keys.add(DslManager.DEFAULT_ALIAS);
        return keys;
    }

    /**
     * 向 DDL 管理器注入上下文参数。
     *
     * <p>注入顺序：先方言后数据源。参数缺失不在此处抛异常——
     * 非 SQL 数据源本来就没有 {@code DataSource}，是否必需由实现方在使用时
     * 通过 {@link DslManager#requireContext()} 自行判定。</p>
     *
     * @param manager    DDL 管理器实例
     * @param dataSource 已解析的 JDBC 数据源，可为 null
     */
    protected void injectDdlContext(DslManager manager, javax.sql.DataSource dataSource) {
        Dialect dialect = getDialect();
        if (dialect != null && manager instanceof DialectAware aware) {
            aware.setDialect(dialect);
        }
        if (dataSource != null && manager instanceof DataSourceAware aware) {
            aware.setDataSource(dataSource);
        }
        if (manager instanceof EngineAware aware) {
            aware.setEngine(this);
        }
    }

    /**
     * 解析默认数据源中的 JDBC 数据源。
     *
     * @return JDBC 数据源；默认数据源缺失或非 JDBC 类型时返回 null
     */
    protected javax.sql.DataSource resolveJdbcDataSource() {
        EngineDataSource<?> dataSource = getDataSource(getDefaultDataSourceName());
        if (dataSource == null) {
            return null;
        }
        return dataSource.getSource(javax.sql.DataSource.class);
    }

    /**
     * 获取默认数据源的方言协议名，供 DDL 管理器按协议查找 SPI 实现。
     *
     * @return 协议名（如 {@code mysql}）；方言缺失时返回 null
     */
    protected String ddlDialectProtocol() {
        Dialect dialect = getDialect();
        return dialect != null ? dialect.protocol() : null;
    }

    /**
     * 获取用户管理器入口（与 meta() 同模式）。
     * <p>默认实现抛出 UnsupportedOperationException，由具备
     * 用户管理能力的引擎子类或 SPI 环境覆盖。</p>
     *
     * @return UserManager 实例
     */
    public UserManager user() {
        throw new UnsupportedOperationException("当前引擎不支持用户管理");
    }
}
