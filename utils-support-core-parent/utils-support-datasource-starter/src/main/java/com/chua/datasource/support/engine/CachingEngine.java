// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.ddl.DslManager;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.datasource.support.user.UserManager;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 只读缓存装饰器：以 {@link JdbcEngine} 为唯一写入通道，用 {@link InMemoryEngine} 承载只读副本。
 *
 * <p>写路径全部落回 JDBC，提交成功后把目标表的内存副本整表失效，下次读取时重新装载；
 * 读路径在满足条件时直接对内存副本求值，不访问数据库。这样做的目的不是「读比写快」，
 * 而是把高频、反复、条件简单的小表读全部压到进程内，避免每次查询都付一次网络往返。</p>
 *
 * <h2>三层开关（逐层放行，任一层不放行就不缓存）</h2>
 * <ol>
 *   <li><b>总开关</b> {@value #ENABLED_PROPERTY}（默认 {@code false}）：
 *       仅在 {@link #wrap(JdbcEngine)} 创建装饰器时读取一次；为 {@code false} 时
 *       直接把传入的 delegate 原样返回，<b>不产生任何行为变化</b>。
 *       显式 {@code new CachingEngine(delegate)} 时按属性初始化为实例开关的初值。</li>
 *   <li><b>实例开关</b> {@link #setCacheEnabled(boolean)}：运行期可反复开关，
 *       关闭后所有读写原样委托 delegate（已装载的副本保留，重新打开即可复用）。</li>
 *   <li><b>表白名单</b> {@value #TABLES_PROPERTY}（逗号/分号/空白分隔，或 {@code *} 表示全部）：
 *       未配置时一张表都不缓存——默认不缓存是刻意的，缓存范围必须由使用方显式声明。</li>
 * </ol>
 *
 * <h2>容量与过期</h2>
 * <ul>
 *   <li>{@value #MAX_ROWS_PROPERTY}（默认 {@value #DEFAULT_MAX_ROWS}）：装载前先
 *       {@code COUNT(*)} 一次，超过阈值即标记该表「旁路」，读路径直接走 JDBC；
 *       旁路是软状态，不会每次读都重新 COUNT，只有 TTL 到期或 {@link #reload(String)} 才重探。</li>
 *   <li>{@value #MAX_TOTAL_ROWS_PROPERTY}（默认 {@value #DEFAULT_MAX_TOTAL_ROWS}）：
 *       全部副本的行数上限，超限按 LRU 逐出最久未访问的表。</li>
 *   <li>{@value #TTL_SECONDS_PROPERTY}（默认 {@value #DEFAULT_TTL_SECONDS}，即不过期）：
 *       正数表示副本最长存活秒数，到期后下次读取重新装载（也用于重探旁路表）。</li>
 * </ul>
 *
 * <h2>边界（务必知悉，不是缺陷）</h2>
 * <ul>
 *   <li><b>最终一致</b>：副本是「装载那一刻」的快照。别的进程/实例写入后，
 *       直到本进程下次写同一张表、TTL 到期或 {@link #reload(String)} 之前，
 *       读到的仍是旧数据。需要跨实例强一致时注册 {@link CacheInvalidator}。</li>
 *   <li><b>只缓存 Lambda ORM 读</b>：{@code querySql} / {@code executeSql} / {@code count(String, ...)}
 *       等原生 SQL 一律直落 delegate，不做缓存也不做语句解析。</li>
 *   <li><b>复杂查询不缓存</b>：带 JOIN、GROUP BY、HAVING、显式投影列或 keyset 游标的查询
 *       无法用内存副本等价复现，直接走 delegate。</li>
 *   <li><b>内存分支没有游标</b>：{@link Page#getNextCursor()} 恒为 {@code null}，
 *       调用方按页码翻页（与 {@link AbstractEngine} 的内存分页口径一致）。</li>
 *   <li><b>行对象共享</b>：副本返回的是快照内的同一批行引用，调用方不应就地修改；
 *       需要与缓存隔离时由调用方自行拷贝。这也是写路径不做行级更新的原因——
 *       就地改写共享行会污染其他读者，索引维护还会带来写放大。</li>
 *   <li><b>只适合小表</b>：大表（超过 {@value #MAX_ROWS_PROPERTY}）不要放进白名单，
 *       否则每次装载都是一次全表扫描。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 总开关打开时返回装饰器，关闭时原样返回 delegate
 * Engine engine = CachingEngine.wrap(mySqlEngine);
 * engine.addDataSource("main", dataSource);   // 数据源仍然注册在 delegate 上
 * List<User> users = engine.query(User.class).eq(User::getStatus, 1).list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class CachingEngine extends AbstractEngine {

    /**
     * 总开关属性名，默认 {@code false}。
     */
    public static final String ENABLED_PROPERTY = "engine.cache.enabled";

    /**
     * 表白名单属性名，逗号/分号/空白分隔，{@code *} 表示全部。
     */
    public static final String TABLES_PROPERTY = "engine.cache.tables";

    /**
     * 单表行数上限属性名，超过即旁路。
     */
    public static final String MAX_ROWS_PROPERTY = "engine.cache.max-rows";

    /**
     * 全部副本行数上限属性名，超限按 LRU 逐出。
     */
    public static final String MAX_TOTAL_ROWS_PROPERTY = "engine.cache.max-total-rows";

    /**
     * 副本存活秒数属性名，{@code 0} 表示不过期。
     */
    public static final String TTL_SECONDS_PROPERTY = "engine.cache.ttl-seconds";

    /**
     * 单表行数默认上限。
     */
    public static final int DEFAULT_MAX_ROWS = 50_000;

    /**
     * 全部副本行数默认上限。
     */
    public static final int DEFAULT_MAX_TOTAL_ROWS = 200_000;

    /**
     * 副本默认存活秒数（0 = 不过期）。
     */
    public static final long DEFAULT_TTL_SECONDS = 0L;

    /**
     * 白名单通配符。
     */
    private static final String WILDCARD = "*";

    private static final Logger log = LoggerFactory.getLogger(CachingEngine.class);

    /**
     * 写入与读数的唯一通道。
     */
    private final JdbcEngine delegate;

    /**
     * 冷装载按表分片互斥，同表只装载一次，命中读取不取得该锁。
     */
    private final Object[] loadLocks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(i -> new Object()).toArray();

    /** 失效代数，拒绝发布失效之前开始装载的快照。 */
    private final AtomicLong cacheGeneration = new AtomicLong();

    /**
     * 仅保护容量核算、发布和失效，不在此锁内访问 delegate。
     */
    private final Object cacheLock = new Object();

    /**
     * 已装载表的独立快照；命中仅读并发映射和更新访问时间。
     */
    private final Map<String, TableCache> tableCaches = new ConcurrentHashMap<>();

    /**
     * 跨实例失效通知器，默认空列表（不做任何通知）。
     */
    private final List<CacheInvalidator> invalidators = new CopyOnWriteArrayList<>();

    /**
     * 实例级开关。
     */
    private volatile boolean enabled;

    /**
     * 表白名单（小写），{@code *} 表示全部。
     */
    private volatile Set<String> cacheTables;

    /**
     * 单表行数上限。
     */
    private volatile int maxRows = DEFAULT_MAX_ROWS;

    /**
     * 全部副本行数上限。
     */
    private volatile int maxTotalRows = DEFAULT_MAX_TOTAL_ROWS;

    /**
     * 副本存活秒数。
     */
    private volatile long ttlSeconds = DEFAULT_TTL_SECONDS;

    /**
     * 创建装饰器，实例开关取 {@value #ENABLED_PROPERTY} 的当前值。
     *
     * @param delegate 被装饰的 JDBC 引擎，不允许为 null
     * @throws IllegalArgumentException delegate 为 null 时抛出
     */
    public CachingEngine(JdbcEngine delegate) {
        this(delegate, Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false").trim()));
    }

    /**
     * 创建装饰器并显式指定实例开关初值。
     *
     * @param delegate 被装饰的 JDBC 引擎，不允许为 null
     * @param enabled  实例开关初值
     * @throws IllegalArgumentException delegate 为 null 时抛出
     */
    public CachingEngine(JdbcEngine delegate, boolean enabled) {
        if (delegate == null) {
            throw new IllegalArgumentException("被装饰的 JdbcEngine 不能为空");
        }
        this.delegate = delegate;
        this.enabled = enabled;
        this.cacheTables = parseTables(System.getProperty(TABLES_PROPERTY, ""));
        this.maxRows = intProperty(MAX_ROWS_PROPERTY, DEFAULT_MAX_ROWS);
        this.maxTotalRows = intProperty(MAX_TOTAL_ROWS_PROPERTY, DEFAULT_MAX_TOTAL_ROWS);
        this.ttlSeconds = longProperty(TTL_SECONDS_PROPERTY, DEFAULT_TTL_SECONDS);
    }

    /**
     * 按总开关包装引擎：关闭时原样返回 delegate，打开时返回装饰器。
     *
     * @param delegate 被装饰的 JDBC 引擎
     * @return 关闭时返回 delegate 本身，打开时返回 {@link CachingEngine} 实例
     */
    public static Engine wrap(JdbcEngine delegate) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false").trim())) {
            return delegate;
        }
        return new CachingEngine(delegate, true);
    }

    /**
     * 无条件创建装饰器（实例开关默认打开），供代码显式启用缓存的场景使用。
     *
     * @param delegate 被装饰的 JDBC 引擎
     * @return 装饰器实例
     */
    public static CachingEngine create(JdbcEngine delegate) {
        return new CachingEngine(delegate, true);
    }

    /**
     * 获取被装饰的引擎。
     *
     * @return delegate
     */
    public JdbcEngine getDelegate() {
        return delegate;
    }

    /**
     * 实例开关当前值。
     *
     * @return true 表示允许缓存（仍需通过表白名单）
     */
    public boolean isCacheEnabled() {
        return enabled;
    }

    /**
     * 运行期开关缓存。关闭后读写全部直落 delegate，已装载的副本保留。
     *
     * @param enabled true 开启
     * @return this
     */
    public CachingEngine setCacheEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * 覆盖表白名单（元素大小写不敏感，{@code *} 表示全部）。
     *
     * @param tables 表名集合，null 或空表示不缓存任何表
     * @return this
     */
    public CachingEngine setCacheTables(java.util.Collection<String> tables) {
        Set<String> parsed = new LinkedHashSet<>();
        if (tables != null) {
            for (String table : tables) {
                if (table != null && !table.isBlank()) {
                    parsed.add(table.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.cacheTables = Collections.unmodifiableSet(parsed);
        return this;
    }

    /**
     * 追加一张缓存表。
     *
     * @param table 表名
     * @return this
     */
    public CachingEngine addCacheTable(String table) {
        if (table == null || table.isBlank()) {
            return this;
        }
        Set<String> parsed = new LinkedHashSet<>(this.cacheTables);
        parsed.add(table.trim().toLowerCase(Locale.ROOT));
        this.cacheTables = Collections.unmodifiableSet(parsed);
        return this;
    }

    /**
     * 设置单表行数上限，超过即旁路该表。
     *
     * @param maxRows 行数上限，必须大于 0
     * @return this
     */
    public CachingEngine setMaxRows(int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("单表缓存行数上限必须大于 0");
        }
        this.maxRows = maxRows;
        evictAll();
        return this;
    }

    /**
     * 设置全部副本行数上限，超限按 LRU 逐出。
     *
     * @param maxTotalRows 行数上限，必须大于 0
     * @return this
     */
    public CachingEngine setMaxTotalRows(int maxTotalRows) {
        if (maxTotalRows <= 0) {
            throw new IllegalArgumentException("缓存总行数上限必须大于 0");
        }
        this.maxTotalRows = maxTotalRows;
        evictAll();
        return this;
    }

    /**
     * 设置副本存活秒数。
     *
     * @param ttlSeconds 秒数，{@code 0} 表示不过期
     * @return this
     */
    public CachingEngine setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("缓存存活秒数不能为负数");
        }
        this.ttlSeconds = ttlSeconds;
        return this;
    }

    /**
     * 注册跨实例失效通知器。
     *
     * @param invalidator 通知器，null 忽略
     * @return this
     */
    public CachingEngine addInvalidator(CacheInvalidator invalidator) {
        if (invalidator != null && invalidator != CacheInvalidator.NONE) {
            invalidators.add(invalidator);
        }
        return this;
    }

    /**
     * 移除跨实例失效通知器。
     *
     * @param invalidator 通知器
     * @return this
     */
    public CachingEngine removeInvalidator(CacheInvalidator invalidator) {
        invalidators.remove(invalidator);
        return this;
    }

    /**
     * 丢弃本地副本，下次读取重新装载。也用于把旁路表翻回可缓存状态。
     *
     * <p>本方法只动本地副本，不触发 {@link CacheInvalidator}——
     * 它本身就是「收到远端通知后要执行的动作」，再通知一次会形成回环。</p>
     *
     * @param table 表名；{@code null} 表示全部失效
     */
    public void reload(String table) {
        if (table == null) {
            evictAll();
            return;
        }
        evictLocal(table);
    }

    /**
     * 当前持有副本的表名（不含旁路表）。
     *
     * @return 表名集合（快照）
     */
    public Set<String> cachedTables() {
        synchronized (cacheLock) {
            Set<String> tables = new LinkedHashSet<>();
            for (Map.Entry<String, TableCache> entry : tableCaches.entrySet()) {
                if (!entry.getValue().bypass()) {
                    tables.add(entry.getKey());
                }
            }
            return tables;
        }
    }

    /**
     * 指定表是否处于旁路状态（行数超限）。
     *
     * @param table 表名
     * @return true 表示该表被判定为「太大，不缓存」
     */
    public boolean isBypassed(String table) {
        if (table == null) {
            return false;
        }
        synchronized (cacheLock) {
            TableCache cache = tableCaches.get(table);
            return cache != null && cache.bypass();
        }
    }

    /**
     * 缓存运行状态快照。
     *
     * @return 状态快照
     */
    public CacheStats stats() {
        synchronized (cacheLock) {
            int tables = 0;
            int rows = 0;
            int bypassed = 0;
            for (TableCache cache : tableCaches.values()) {
                if (cache.bypass()) {
                    bypassed++;
                } else {
                    tables++;
                    rows += cache.rowCount();
                }
            }
            return new CacheStats(tables, rows, bypassed);
        }
    }

    // ==================== 读路径 ====================

    /**
     * 读取：命中副本时对内存求值，否则委托 delegate。
     *
     * @param wrapper     查询包装器
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 查询结果
     */
    @Override
    public <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper, Class<T> entityClass) {
        EngineQueryWrapper<T> engineWrapper = asEngineWrapper(wrapper);
        if (engineWrapper == null || !enabled) {
            return delegate.executeQuery(wrapper, entityClass);
        }
        QuerySql<T> sql = wrapper.buildSql();
        String table = resolveTableName(engineWrapper.getEntityClass());
        TableCache cache = readableQuery(sql) && cacheableTable(table)
                ? loadTable(table, engineWrapper.getEntityClass()) : null;
        if (cache == null || cache.bypass()) {
            return delegate.executeQuery(wrapper, entityClass);
        }
        Object[] params = sql.params().toArray(new Object[0]);
        return interceptQuery(sql.whereClause(), params,
                () -> truncate(cache.replica().evaluateQuery(engineWrapper), wrapper));
    }

    /**
     * 分页读取：命中副本时在内存切片，否则委托 delegate。
     *
     * @param wrapper     查询包装器
     * @param entityClass 实体类型
     * @param pn          页码，从 1 开始
     * @param ps          每页条数
     * @param <T>         实体泛型
     * @return 分页结果
     */
    @Override
    public <T> Page<T> executePage(LambdaQueryWrapper<T> wrapper, Class<T> entityClass, int pn, int ps) {
        if (pn < 1) {
            pn = 1;
        }
        if (ps < 1) {
            throw new IllegalArgumentException("每页条数必须大于 0");
        }
        EngineQueryWrapper<T> engineWrapper = asEngineWrapper(wrapper);
        if (engineWrapper == null || !enabled) {
            return delegate.executePage(wrapper, entityClass, pn, ps);
        }
        QuerySql<T> sql = wrapper.buildSql();
        String table = resolveTableName(engineWrapper.getEntityClass());
        TableCache cache = readableQuery(sql) && cacheableTable(table)
                ? loadTable(table, engineWrapper.getEntityClass()) : null;
        if (cache == null || cache.bypass()) {
            return delegate.executePage(wrapper, entityClass, pn, ps);
        }
        Object[] params = sql.params().toArray(new Object[0]);
        final int pageNum = pn;
        return interceptPage(sql.whereClause(), params,
                () -> readPage(cache.replica(), engineWrapper, pageNum, ps));
    }

    /**
     * 在拦截器回调包裹下执行一次分页读取。
     *
     * <p>{@link #interceptQuery} 的结果类型是 {@code List}，无法承载分页元数据，
     * 因此这里按同一套 {@code beforeQuery / afterQuery / onError} 顺序单独包装；
     * {@code afterQuery} 收到的是当前页记录，与物理分页路径的语义一致。</p>
     *
     * @param ql     查询语句或 WHERE 条件子句
     * @param params 绑定参数
     * @param action 实际查询动作
     * @param <T>    结果元素类型
     * @return {@code action} 的执行结果
     */
    private <T> Page<T> interceptPage(String ql, Object[] params, Supplier<Page<T>> action) {
        for (EngineInterceptor interceptor : interceptors()) {
            interceptor.beforeQuery(ql, params);
        }
        try {
            Page<T> page = action.get();
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.afterQuery(ql, params, page.records());
            }
            return page;
        } catch (RuntimeException re) {
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * 计数：命中副本时对内存求值，否则委托 delegate。
     *
     * @param wrapper 查询包装器
     * @param <T>     实体泛型
     * @return 命中行数
     */
    @Override
    public <T> long queryCount(LambdaQueryWrapper<T> wrapper) {
        EngineQueryWrapper<T> engineWrapper = asEngineWrapper(wrapper);
        if (engineWrapper == null || !enabled) {
            return delegate.queryCount(wrapper);
        }
        QuerySql<T> sql = wrapper.buildSql();
        String table = resolveTableName(engineWrapper.getEntityClass());
        TableCache cache = readableQuery(sql) && cacheableTable(table)
                ? loadTable(table, engineWrapper.getEntityClass()) : null;
        if (cache == null || cache.bypass()) {
            return delegate.queryCount(wrapper);
        }
        return cache.replica().evaluateQuery(engineWrapper).size();
    }

    /**
     * 对内存副本求值并切页。
     *
     * @param wrapper  查询包装器
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param <T>      实体泛型
     * @return 分页结果，nextCursor 恒为 null
     */
    private <T> Page<T> readPage(InMemoryEngine replica, EngineQueryWrapper<T> wrapper,
                               int pageNum, int pageSize) {
        if (wrapper.getLimit() <= 0) {
            // 未额外设 limit：分页语义与内存引擎完全一致，直接复用其求值入口
            return replica.evaluatePage(wrapper, pageNum, pageSize);
        }
        // 调用方同时设了 limit 与页码：先按 limit 截断再切页，与 delegate 的非物理分页路径一致
        List<T> rows = truncate(replica.evaluateQuery(wrapper), wrapper);
        int from = (pageNum - 1) * pageSize;
        if (from >= rows.size()) {
            return new Page<>(pageNum, pageSize, rows.size(), Collections.emptyList());
        }
        int to = Math.min(from + pageSize, rows.size());
        return new Page<>(pageNum, pageSize, rows.size(), Wraps.list(rows.subList(from, to)));
    }

    // ==================== 写路径 ====================

    /**
     * 更新：先落库，成功后整表失效。
     *
     * @param sql 更新 SQL 信息
     * @param <T> 实体泛型
     * @return 影响行数
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        int affected = delegate.executeUpdate(sql);
        invalidateAfterWrite(resolveTableName(sql.entityClass()));
        return affected;
    }

    /**
     * 删除：先落库，成功后整表失效。
     *
     * @param sql 删除 SQL 信息
     * @param <T> 实体泛型
     * @return 影响行数
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        int affected = delegate.executeDelete(sql);
        invalidateAfterWrite(resolveTableName(sql.entityClass()));
        return affected;
    }

    /**
     * 单行插入：落库成功后整表失效。
     *
     * @param entityClass 实体类型
     * @param entity      待插入实体
     * @param <T>         实体泛型
     * @return 影响行数
     */
    @Override
    public <T> int insert(Class<T> entityClass, T entity) {
        int affected = delegate.insert(entityClass, entity);
        invalidateAfterWrite(resolveTableName(entityClass));
        return affected;
    }

    /**
     * 批量插入：落库成功后整表失效。
     *
     * @param entityClass 实体类型
     * @param entities    待插入实体列表
     * @param <T>         实体泛型
     * @return 影响行数合计
     */
    @Override
    public <T> int insertBatch(Class<T> entityClass, List<T> entities) {
        int affected = delegate.insertBatch(entityClass, entities);
        invalidateAfterWrite(resolveTableName(entityClass));
        return affected;
    }

    /**
     * 原生写入：无法可靠解析目标表，成功后全部副本失效。
     *
     * @param ql     数据操作语句
     * @param params 参数
     * @return 影响行数
     */
    @Override
    public int executeSql(String ql, Object... params) {
        int affected = delegate.executeSql(ql, params);
        invalidateAllAfterWrite();
        return affected;
    }

    /**
     * 原生批量写入：成功后全部副本失效。
     *
     * @param ql          语句模板
     * @param batchParams 每组参数
     * @return 每组参数对应的受影响行数
     */
    @Override
    public int[] batchSql(String ql, List<Object[]> batchParams) {
        int[] affected = delegate.batchSql(ql, batchParams);
        invalidateAllAfterWrite();
        return affected;
    }

    /**
     * 写入内存存储：委托 delegate，并失效本表副本。
     *
     * @param name 表名
     * @param data 行数据
     * @param <T>  行类型
     * @return this
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        delegate.store(name, data);
        if (name != null) {
            invalidateAfterWrite(name);
        }
        return this;
    }

    // ==================== 缓存装载与失效 ====================

    /**
     * 确保表已装载到副本。
     *
     * @param table       表名
     * @param entityClass 实体类型
     * @return 当前版本的独立快照；并发失效时返回 null，调用方直接查询 delegate
     */
    private TableCache loadTable(String table, Class<?> entityClass) {
        TableCache ready = tableCaches.get(table);
        if (ready != null && !expired(ready)) {
            ready.lastAccessNanos().set(System.nanoTime());
            return ready;
        }
        Object loadLock = loadLocks[(table.hashCode() & Integer.MAX_VALUE) % loadLocks.length];
        synchronized (loadLock) {
            TableCache cache = tableCaches.get(table);
            if (cache != null && !expired(cache)) {
                cache.lastAccessNanos().set(System.nanoTime());
                return cache;
            }
            long generation = cacheGeneration.get();
            int rowLimit = Math.min(maxRows, maxTotalRows);
            long total = delegate.queryCount(delegate.query(entityClass));
            TableCache loaded;
            if (total > rowLimit) {
                loaded = new TableCache(clamp(total), true, null);
            } else {
                // COUNT 与 SELECT 之间可能增长，最多读取阈值 + 1 行并再次校验。
                int fetchLimit = rowLimit == Integer.MAX_VALUE ? rowLimit : rowLimit + 1;
                List<?> rows = delegate.query(entityClass).limit(fetchLimit).list();
                if (rows.size() > rowLimit) {
                    loaded = new TableCache(rows.size(), true, null);
                } else {
                    InMemoryEngine snapshot = new InMemoryEngine();
                    snapshot.store(table, rows);
                    snapshot.index();
                    loaded = new TableCache(rows.size(), false, snapshot);
                }
            }
            synchronized (cacheLock) {
                if (generation != cacheGeneration.get() || !enabled || isClosed()) {
                    return null;
                }
                tableCaches.remove(table);
                if (!loaded.bypass()) {
                    while ((long) cachedRows() + loaded.rowCount() > maxTotalRows && !tableCaches.isEmpty()) {
                        evictEldest();
                    }
                }
                tableCaches.put(table, loaded);
            }
            return loaded;
        }
    }

    /**
     * 副本是否已过期。
     *
     * @param cache 表状态
     * @return true 表示需重新装载
     */
    private boolean expired(TableCache cache) {
        long ttl = ttlSeconds;
        if (ttl <= 0) {
            return false;
        }
        return (System.nanoTime() - cache.loadedAtNanos()) / 1_000_000_000L >= ttl;
    }

    /**
     * 全部副本的缓存行数。
     *
     * @return 行数合计
     */
    private int cachedRows() {
        int total = 0;
        for (TableCache cache : tableCaches.values()) {
            if (!cache.bypass()) {
                total += cache.rowCount();
            }
        }
        return total;
    }

    /**
     * 逐出最久未访问的一张表。
     */
    private void evictEldest() {
        Map.Entry<String, TableCache> eldest = null;
        for (Map.Entry<String, TableCache> entry : tableCaches.entrySet()) {
            if (eldest == null || entry.getValue().lastAccessNanos().get()
                    < eldest.getValue().lastAccessNanos().get()) {
                eldest = entry;
            }
        }
        if (eldest != null) {
            tableCaches.remove(eldest.getKey());
            log.debug("缓存逐出表 {}（{} 行）", eldest.getKey(), eldest.getValue().rowCount());
        }
    }

    /**
     * 写入成功后失效目标表并通知外部。
     *
     * @param table 表名
     */
    private void invalidateAfterWrite(String table) {
        evictLocal(table);
        notifyInvalidators(table);
    }

    /**
     * 原生写入成功后失效全部副本并通知外部。
     */
    private void invalidateAllAfterWrite() {
        evictAll();
        notifyInvalidators(null);
    }

    /**
     * 失效单表副本。
     *
     * @param table 表名
     */
    private void evictLocal(String table) {
        synchronized (cacheLock) {
            cacheGeneration.incrementAndGet();
            tableCaches.remove(table);
        }
    }

    /**
     * 失效全部副本。
     */
    private void evictAll() {
        synchronized (cacheLock) {
            cacheGeneration.incrementAndGet();
            tableCaches.clear();
        }
    }

    /**
     * 通知全部跨实例失效监听器，单个监听器失败不影响写入结果。
     *
     * @param table 表名，null 表示全部
     */
    private void notifyInvalidators(String table) {
        for (CacheInvalidator invalidator : invalidators) {
            try {
                invalidator.onInvalidate(table);
            } catch (RuntimeException e) {
                log.warn("缓存失效通知失败 table={}: {}", table, e.getMessage(), e);
            }
        }
    }

    /**
     * 当前查询是否能用内存副本等价复现。
     *
     * @param sql 查询 SQL 信息
     * @return true 表示可以走副本
     */
    private static boolean readableQuery(QuerySql<?> sql) {
        return !sql.hasJoins() && !sql.hasGroupBy() && !sql.hasHaving()
                && !sql.hasSelect() && !sql.hasCursor();
    }

    /**
     * 表是否命中白名单（大小写不敏感，{@code *} 表示全部）。
     *
     * @param table 表名
     * @return true 表示该表允许缓存
     */
    private boolean cacheableTable(String table) {
        Set<String> tables = this.cacheTables;
        if (tables.isEmpty()) {
            return false;
        }
        return tables.contains(WILDCARD) || tables.contains(table.toLowerCase(Locale.ROOT));
    }

    /**
     * 按调用方设置的 limit/offset 截取，语义与 {@link AbstractEngine} 的内存后处理一致。
     *
     * @param rows    全量结果
     * @param wrapper 查询包装器
     * @param <T>     实体泛型
     * @return 截取后的独立副本
     */
    private static <T> List<T> truncate(List<T> rows, LambdaQueryWrapper<T> wrapper) {
        int limit = wrapper.getLimit();
        if (limit <= 0) {
            return rows;
        }
        int from = Math.max(0, wrapper.getOffset());
        if (from >= rows.size()) {
            return Collections.emptyList();
        }
        return Wraps.list(rows.subList(from, Math.min(from + limit, rows.size())));
    }

    /**
     * 判断包装器是否为引擎自建类型（只有它才能直接交给副本求值）。
     *
     * @param wrapper 查询包装器
     * @param <T>     实体泛型
     * @return 引擎自建包装器，或 null
     */
    @SuppressWarnings("unchecked")
    private static <T> EngineQueryWrapper<T> asEngineWrapper(LambdaQueryWrapper<T> wrapper) {
        return wrapper instanceof EngineQueryWrapper ? (EngineQueryWrapper<T>) wrapper : null;
    }

    /**
     * 行数收敛到 int 范围。
     *
     * @param value 原始行数
     * @return 收敛后的行数
     */
    private static int clamp(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * 解析表白名单属性值。
     *
     * @param raw 原始值
     * @return 小写表名集合（不可变）
     */
    private static Set<String> parseTables(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> tables = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                tables.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(tables);
    }

    /**
     * 读取整型属性，非法值回退默认值。
     *
     * @param key          属性名
     * @param defaultValue 默认值
     * @return 属性值
     */
    private static int intProperty(String key, int defaultValue) {
        try {
            String raw = System.getProperty(key);
            return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 读取长整型属性，非法值回退默认值。
     *
     * @param key          属性名
     * @param defaultValue 默认值
     * @return 属性值
     */
    private static long longProperty(String key, long defaultValue) {
        try {
            String raw = System.getProperty(key);
            return raw == null || raw.isBlank() ? defaultValue : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== 其余能力全部委托 delegate ====================

    /**
     * 委托 delegate 执行底层查询（抽象方法实现）。
     *
     * @param where       WHERE 子句
     * @param params      参数
     * @param entityClass 实体类型
     * @param limit       限制
     * @param offset      偏移
     * @param <T>         实体泛型
     * @return 查询结果
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        return delegate.executeNewQuery(where, params, entityClass, limit, offset);
    }

    /**
     * 委托 delegate 的物理分页能力位。
     *
     * @param entityClass 实体类型
     * @return true 表示 delegate 支持物理分页
     */
    @Override
    protected boolean supportsNativePaging(Class<?> entityClass) {
        return delegate.supportsNativePaging(entityClass);
    }

    /**
     * 委托 delegate 的游标分页能力位。
     *
     * @return true 表示 delegate 支持游标分页
     */
    @Override
    public boolean supportsCursorPaging() {
        return delegate.supportsCursorPaging();
    }

    /**
     * 委托 delegate 注册数据源。
     *
     * @param name 名称
     * @param ds   数据源
     * @param <T>  底层源类型
     * @return this
     */
    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        delegate.addDataSource(name, ds);
        return this;
    }

    /**
     * 委托 delegate 设置默认数据源名称。
     *
     * @param name 名称
     * @return this
     */
    @Override
    public Engine setDefaultDataSourceName(String name) {
        delegate.setDefaultDataSourceName(name);
        return this;
    }

    /**
     * 委托 delegate 返回默认数据源名称。
     *
     * @return 名称
     */
    @Override
    public String getDefaultDataSourceName() {
        return delegate.getDefaultDataSourceName();
    }

    /**
     * 委托 delegate 获取执行器。
     *
     * @param dataSourceName 数据源名称
     * @return 执行器
     */
    @Override
    public SqlExecutor getExecutor(String dataSourceName) {
        return delegate.getExecutor(dataSourceName);
    }

    /**
     * 委托 delegate 获取默认执行器。
     *
     * @return 执行器
     */
    @Override
    public SqlExecutor getExecutor() {
        return delegate.getExecutor();
    }

    /**
     * 委托 delegate 获取数据源。
     *
     * @param name 数据源名称
     * @param <T>  底层源类型
     * @return 数据源
     */
    @Override
    public <T> EngineDataSource<T> getDataSource(String name) {
        return delegate.getDataSource(name);
    }

    /**
     * 委托 delegate 获取默认数据源。
     *
     * @param <T> 底层源类型
     * @return 数据源
     */
    @Override
    public <T> EngineDataSource<T> getDataSource() {
        return delegate.getDataSource();
    }

    /**
     * 委托 delegate 列出数据源名称。
     *
     * @return 名称集合
     */
    @Override
    public java.util.Set<String> dataSourceNames() {
        return delegate.dataSourceNames();
    }

    /**
     * 委托 delegate 摘除数据源。
     *
     * @param name 名称
     * @return this
     */
    @Override
    public Engine removeDataSource(String name) {
        delegate.removeDataSource(name);
        return this;
    }

    /**
     * 委托 delegate 获取方言。
     *
     * @param dataSourceName 数据源名称
     * @return 方言
     */
    @Override
    public Dialect getDialect(String dataSourceName) {
        return delegate.getDialect(dataSourceName);
    }

    /**
     * 委托 delegate 获取元数据。
     *
     * @return 元数据
     */
    @Override
    public MetaData meta() {
        return delegate.meta();
    }

    /**
     * 委托 delegate 获取 DDL 管理器。
     *
     * @return DDL 管理器
     */
    @Override
    public DslManager ddl() {
        return delegate.ddl();
    }

    /**
     * 委托 delegate 获取用户管理器。
     *
     * @return 用户管理器
     */
    @Override
    public UserManager user() {
        return delegate.user();
    }

    /**
     * 委托 delegate 设置隧道。
     *
     * @param dataSourceName 数据源名称
     * @param tunnel         隧道
     * @return this
     */
    @Override
    public Engine setTunnel(String dataSourceName, Tunnel tunnel) {
        delegate.setTunnel(dataSourceName, tunnel);
        return this;
    }

    /**
     * 委托 delegate 打开隧道。
     *
     * @param dataSourceName 数据源名称
     * @param tunnel         隧道
     * @return 隧道端口
     */
    @Override
    public int openTunnel(String dataSourceName, Tunnel tunnel) {
        return delegate.openTunnel(dataSourceName, tunnel);
    }

    /**
     * 委托 delegate 关闭隧道。
     *
     * @param dataSourceName 数据源名称
     * @return this
     */
    @Override
    public Engine closeTunnel(String dataSourceName) {
        delegate.closeTunnel(dataSourceName);
        return this;
    }

    /**
     * 是否已关闭：本装饰器或 delegate 任一关闭即为关闭。
     *
     * @return true 表示已关闭
     */
    @Override
    public boolean isClosed() {
        return super.isClosed() || delegate.isClosed();
    }

    /**
     * 关闭副本、delegate 与本装饰器自身。
     */
    @Override
    public void close() {
        synchronized (cacheLock) {
            super.close();
            evictAll();
        }
        try {
            delegate.close();
        } finally {
            invalidators.clear();
        }
    }

    /**
     * 单表缓存状态。
     *
     * @param rowCount       装载时的行数
     * @param bypass         是否因超限被旁路
     * @param loadedAtNanos  装载时刻（单调时间）
     * @param replica        完整发布的独立只读副本，旁路表为 null
     * @param lastAccessNanos 最近访问时刻，用于装载时逐出最久未使用的表
     */
    private record TableCache(int rowCount, boolean bypass, long loadedAtNanos,
                              InMemoryEngine replica, AtomicLong lastAccessNanos) {
        private TableCache(int rowCount, boolean bypass, InMemoryEngine replica) {
            this(rowCount, bypass, System.nanoTime(), replica, new AtomicLong(System.nanoTime()));
        }
    }

    /**
     * 缓存运行状态快照。
     *
     * @param cachedTables   持有副本的表数
     * @param cachedRows     副本行数合计
     * @param bypassedTables 被旁路的表数
     */
    public record CacheStats(int cachedTables, int cachedRows, int bypassedTables) {
    }
}
