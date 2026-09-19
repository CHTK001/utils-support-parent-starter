package com.chua.greptimedb.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.JoinClause;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.greptimedb.support.client.GreptimeDbClient;
import com.chua.greptimedb.support.client.GreptimeJdbcClient;
import com.chua.greptimedb.support.client.GreptimeJdbcClient.JdbcResult;
import com.chua.greptimedb.support.datasource.GreptimeDbEngineDataSource;
import io.greptime.GreptimeDB;
import io.greptime.models.Err;
import io.greptime.models.Result;
import io.greptime.models.Table;
import io.greptime.models.WriteOk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * greptimedb 时序数据库引擎实现。
 * <p>
 * <b>写入</b>走官方 gRPC Ingester SDK（{@link #write} / 流式 / Bulk）；
 * <b>查询、分页、计数与删除</b>按官方文档推荐，经 <b>MySQL 协议(默认 4002 端口) JDBC 驱动</b>
 * 执行真实参数化 SQL：投影列、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、
 * LIMIT/OFFSET 全部下推到服务端执行，不做内存重排与内存截断。
 * 通过 SPI 注册为 {@code "greptimedb"}。
 * </p>
 * <p>
 * 时序库语义说明：
 * <ul>
 *   <li>不支持 {@code UPDATE} —— 相同 tag + 时间戳重复写入即为覆盖(upsert)，
 *       见 {@link #executeUpdate}</li>
 *   <li>{@code DELETE} 要求 WHERE 条件命中 tag/time 列（服务端校验），返回真实影响行数</li>
 *   <li>不支持内存旁路存储 {@link #store(String, List)}，数据只能真实落库</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("greptimedb")
public class GreptimeDbEngine extends AbstractEngine {

    /**
     * 日志记录器
     */
    private static final Logger log = LoggerFactory.getLogger(GreptimeDbEngine.class);

    /**
     * 缺省数据库名
     */
    private static final String DEFAULT_DATABASE = "public";

    /**
     * 实体字段映射缓存：类 -> (snake_大小写 列名 -> 字段)
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 覆盖用的 JDBC URL；为空时按 gRPC 端点主机 + 4002 端口推导。
     * <p>该覆盖对全部数据源生效，多数据源且端口各异时请只注册一份数据源或改用统一入口。</p>
     */
    private volatile String jdbcUrlOverride;

    /**
     * 引擎是否已关闭；关闭后禁止再取用客户端，重新 {@code addDataSource} 会复位。
     * <p>Engine 经 SPI 以单例形式复用，故该标志在注册数据源时必须重置。</p>
     */
    private volatile boolean closed;

    /**
     * JDBC 客户端缓存：数据源名称 -> （配置指纹 + 客户端），配置变更时自动重建。
     */
    private final Map<String, JdbcHolder> jdbcClients = new ConcurrentHashMap<>();

    /**
     * JDBC 客户端持有者，记录构建时使用的配置指纹以便变更检测。
     */
    private static final class JdbcHolder {

        /**
         * 配置指纹：URL + 用户名 + 密码
         */
        private final String fingerprint;

        /**
         * JDBC 客户端
         */
        private final GreptimeJdbcClient client;

        /**
         * 构造持有者。
         *
         * @param fingerprint 配置指纹
         * @param client      JDBC 客户端
         */
        private JdbcHolder(String fingerprint, GreptimeJdbcClient client) {
            this.fingerprint = fingerprint;
            this.client = client;
        }
    }

    /**
     * 覆盖默认 JDBC URL（非标准端口或开启 TLS 时使用）。
     *
     * @param jdbcUrl 形如 {@code jdbc:mysql://host:4002/public?useSSL=false}；传 null/空串表示清除覆盖
     * @return this
     */
    public GreptimeDbEngine setJdbcUrl(String jdbcUrl) {
        if (jdbcUrl != null && !jdbcUrl.isBlank() && !jdbcUrl.trim().startsWith("jdbc:")) {
            throw new IllegalArgumentException("JDBC URL 必须以 jdbc: 开头: " + jdbcUrl);
        }
        this.jdbcUrlOverride = jdbcUrl == null || jdbcUrl.isBlank() ? null : jdbcUrl.trim();
        return this;
    }

    // ==================== 写入（gRPC 官方 SDK） ====================

    /**
     * 写入一张表到 greptimedb（gRPC 异步写入）。
     *
     * @param table 表数据（由 SDK {@link Table} 构建），不可为空
     * @return 写入结果 期货；失败信息在 {@code Result} 的 {@code Err} 分支中，
     *         调用方必须判断 {@code isOk()} 或注册 {@code whenComplete} 处理，否则写入失败会被静默丢弃
     */
    public CompletableFuture<Result<WriteOk, Err>> write(Table table) {
        if (table == null) {
            throw new IllegalArgumentException("写入的 Table 不能为空");
        }
        return client().write(table);
    }

    /**
     * 批量写入多张表到 greptimedb（gRPC 异步写入）。
     *
     * @param tables 表数据，不可为空且元素不可为空
     * @return 写入结果 期货；语义同 {@link #write(Table)}
     */
    public CompletableFuture<Result<WriteOk, Err>> write(Table... tables) {
        if (tables == null || tables.length == 0) {
            throw new IllegalArgumentException("写入的 Table 数组不能为空");
        }
        for (Table table : tables) {
            if (table == null) {
                throw new IllegalArgumentException("写入的 Table 数组存在空元素");
            }
        }
        return client().write(tables);
    }

    /**
     * 获取默认数据源的 greptimedb gRPC 客户端。
     * <p>若默认数据源缺失（如引擎被复用后状态残留），回退到任意已注册的 GreptimeDB 数据源。</p>
     *
     * @return GreptimeDB 客户端，非 空
     */
    public GreptimeDB client() {
        return client(null);
    }

    /**
     * 获取指定数据源的 greptimedb gRPC 客户端。
     *
     * @param name 数据源名称，为 空 时取默认数据源
     * @return GreptimeDB 客户端，非 空
     * @throws IllegalStateException    引擎已关闭或客户端未初始化时抛出
     * @throws IllegalArgumentException 指定名称的数据源不存在时抛出
     */
    public GreptimeDB client(String name) {
        GreptimeDB grpcClient = datasource(name).getSource();
        if (grpcClient == null) {
            throw new IllegalStateException("GreptimeDB gRPC 客户端未初始化或数据源已关闭");
        }
        return grpcClient;
    }

    /**
     * datasource。
     *
     * @return GreptimeDb引擎数据来源 对象，非 空
     * @throws IllegalStateException 未配置数据源或引擎已关闭时抛出
     */
    private GreptimeDbEngineDataSource datasource() {
        return datasource(null);
    }

    /**
     * 解析数据源封装：名称为 空 时取默认数据源（缺失则回退任意已注册数据源），
     * 名称非 空 时要求精确存在，避免静默连到非预期集群。
     *
     * @param name 数据源名称，可为 空
     * @return GreptimeDb引擎数据来源 对象，非 空
     * @throws IllegalStateException    引擎已关闭 / 默认数据源缺失时抛出
     * @throws IllegalArgumentException 指定名称的数据源不存在时抛出
     */
    private GreptimeDbEngineDataSource datasource(String name) {
        if (closed) {
            throw new IllegalStateException("GreptimeDbEngine 已关闭，请重新调用 addDataSource 注册数据源");
        }
        if (name != null) {
            EngineDataSource<Object> pointed = dataSources.get(name);
            if (pointed == null) {
                throw new IllegalArgumentException("GreptimeDB 数据源不存在: " + name);
            }
            return cast(name, pointed);
        }
        EngineDataSource<Object> ds = defaultDataSourceName == null
                ? null : dataSources.get(defaultDataSourceName);
        if (ds == null && !dataSources.isEmpty()) {
            Map.Entry<String, EngineDataSource<Object>> first = dataSources.entrySet().iterator().next();
            ds = first.getValue();
        }
        if (ds == null) {
            throw new IllegalStateException("未配置 GreptimeDB 数据源");
        }
        return cast(defaultDataSourceName, ds);
    }

    /**
     * 将注册表中的条目转换为 GreptimeDB 数据源封装。
     * <p>注册表泛型为 {@code EngineDataSource<Object>}，而实现类参数化为
     * {@code EngineDataSource<GreptimeDB>}，泛型不变导致无法直接 {@code instanceof}，
     * 故先按 {@code Object} 观察再判定。</p>
     *
     * @param name 数据源名称（仅用于异常提示）
     * @param ds   原始数据源
     * @return GreptimeDb引擎数据来源 对象
     * @throws IllegalArgumentException 数据源类型不匹配时抛出
     */
    private static GreptimeDbEngineDataSource cast(String name, EngineDataSource<?> ds) {
        Object raw = ds;
        if (raw instanceof GreptimeDbEngineDataSource gdb) {
            return gdb;
        }
        throw new IllegalArgumentException("数据源类型非法，仅支持 GreptimeDbEngineDataSource: " + name);
    }

    /**
     * 按名称取数据源，不存在时返回 空（用于 {@link #getExecutor(String)} 这类需返回 空 的入口）。
     *
     * @param name 数据源名称，可为 空
     * @return GreptimeDb引擎数据来源 对象，无匹配返回 空
     */
    private GreptimeDbEngineDataSource datasourceOrNull(String name) {
        if (closed || dataSources.isEmpty()) {
            return null;
        }
        if (name != null) {
            EngineDataSource<Object> pointed = dataSources.get(name);
            return pointed == null ? null : cast(name, pointed);
        }
        EngineDataSource<Object> ds = defaultDataSourceName == null
                ? null : dataSources.get(defaultDataSourceName);
        if (ds == null) {
            ds = dataSources.values().iterator().next();
        }
        return cast(name, ds);
    }

    // ==================== 数据源注册 ====================

    /**
     * 添加数据源（greptimedb 客户端或连接串）。
     *
     * @param name       数据源名称，不可为 空
     * @param dataSource 数据源封装
     * @param <T>        底层类型
     * @return this
     * @throws IllegalArgumentException 名称为 空 或底层源类型不受支持时抛出
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        checkName(name);
        if (dataSource == null) {
            throw new IllegalArgumentException("GreptimeDB 数据源不能为空");
        }
        Object source = dataSource.getSource();
        GreptimeDbEngineDataSource wrapped;
        if (source instanceof GreptimeDB grpcClient) {
            wrapped = new GreptimeDbEngineDataSource(name, dataSource.url(),
                    dataSource.username(), dataSource.password(), dataSource.database(), grpcClient);
        } else if (source instanceof String url) {
            GreptimeDB grpcClient = GreptimeDbClient.create(url, dataSource.database(),
                    dataSource.username(), dataSource.password());
            wrapped = new GreptimeDbEngineDataSource(name, url,
                    dataSource.username(), dataSource.password(), dataSource.database(), grpcClient);
        } else {
            throw new IllegalArgumentException("GreptimeDbEngine 仅支持 GreptimeDB 客户端或 URL 字符串，实际为: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        register(name, (EngineDataSource<Object>) (Object) wrapped);
        return this;
    }

    /**
     * 便捷添加数据源（直接创建客户端）。
     *
     * @param name      数据源名称，不可为 空
     * @param endpoint  greptimedb gRPC 端点
     * @param database  数据库名
     * @param username  用户名（为空表示无鉴权）
     * @param password  密码
     * @return this
     * @throws IllegalArgumentException 名称为 空 或端点非法时抛出
     */
    public GreptimeDbEngine addDataSource(String name, String endpoint,
                                          String database, String username, String password) {
        checkName(name);
        GreptimeDB grpcClient = GreptimeDbClient.create(endpoint, database, username, password);
        GreptimeDbEngineDataSource wrapped = new GreptimeDbEngineDataSource(
                name, endpoint, username, password, database, grpcClient);
        register(name, (EngineDataSource<Object>) (Object) wrapped);
        return this;
    }

    /**
     * 登记数据源并维护默认数据源；重新注册会解除 {@code close()} 的关闭标志。
     * <p>同名替换时关闭被弃用的旧客户端，但若新旧数据源持有同一个 gRPC 客户端
     * （调用方用同一实例重复注册），则跳过关闭，避免把正在使用的连接一并关掉。</p>
     *
     * @param name    数据源名称
     * @param wrapped 数据源封装
     */
    private void register(String name, EngineDataSource<Object> wrapped) {
        EngineDataSource<Object> previous = dataSources.put(name, wrapped);
        if (previous != null && previous != wrapped && previous.getSource() != wrapped.getSource()) {
            closeQuietly(previous);
        }
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        // 引擎为 SPI 单例，close() 后重新注册数据源即恢复可用
        this.closed = false;
    }

    /**
     * 静默关闭被替换的旧数据源，忽略关闭异常。
     *
     * @param ds 待关闭数据源
     */
    private static void closeQuietly(EngineDataSource<?> ds) {
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("GreptimeDB 替换数据源时关闭旧客户端失败: name={}", ds.name(), e);
        }
    }

    /**
     * 校验数据源名称（ConcurrentHashMap 不接受 空 键，此处转为可读异常）。
     *
     * @param name 数据源名称
     * @throws IllegalArgumentException 名称为 空 时抛出
     */
    private static void checkName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
    }

    // ==================== 查询（MySQL 协议 JDBC，真库全量下推） ====================

    /**
     * 执行完整查询：投影列、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT/OFFSET
     * 全部渲染为真实参数化 SQL 下推服务端，杜绝全表回捞与内存截断。
     * <p>父类 {@code executeQuery} 负责拦截器回调，本方法只负责 SQL 生成与映射。</p>
     *
     * @param sql 查询 SQL 信息
     * @param <T> 实体类型
     * @return 查询结果
     */
    @Override
    protected <T> List<T> executeQueryFull(QuerySql<T> sql) {
        if (sql == null) {
            throw new IllegalArgumentException("查询条件不能为空");
        }
        Class<T> entityClass = sql.entityClass();
        List<Object> params = new ArrayList<>(sql.params() == null ? List.of() : sql.params());
        if (sql.hasHaving()) {
            params.addAll(sql.havingParams() == null ? List.of() : sql.havingParams());
        }
        String statement = buildSelectSql(entityClass, sql.selectColumns(), sql.joins(),
                sql.whereClause(), sql.groupByColumn(), sql.havingClause(), sql.orderBys(),
                sql.limit(), sql.offset());
        return runQuery(statement, params, entityClass);
    }

    /**
     * 父类抽象方法实现：将 WHERE 条件下推为真实 SQL 查询，并尊重 限制/偏移量 截断。
     *
     * @param where       含 {@code ?} 占位符的条件子句（未归一化列名，本方法内部完成归一化）
     * @param params      占位符参数
     * @param entityClass 实体类型
     * @param limit       返回行数上限，0 或负数表示不限
     * @param offset      跳过行数，0 或负数表示不跳过
     * @param <T>         实体类型参数
     * @return 实体列表
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass,
                                          int limit, int offset) {
        String statement = buildSelectSql(entityClass, List.of(), List.of(), where,
                null, null, List.of(), limit, offset);
        return runQuery(statement, params == null ? List.of() : Arrays.asList(params), entityClass);
    }

    /**
     * 渲染完整 SELECT 语句。
     * <p>子句顺序：SELECT 投影 → FROM 表 → JOIN → WHERE → GROUP BY → HAVING → ORDER BY → 分页。
     * 表名走白名单校验，列名做归一化替换，条件值一律以 {@code ?} 占位符参数化绑定。</p>
     *
     * @param entityClass  实体类型（决定表名与列名映射）
     * @param selectColumns 投影列片段，空集合表示 {@code SELECT *}
     * @param joins        JOIN 子句列表，可为 空
     * @param where        WHERE 片段（不含关键字），可为 空
     * @param groupBy      GROUP BY 片段（不含关键字），可为 空
     * @param having       HAVING 片段（不含关键字），可为 空
     * @param orderBys     ORDER BY 列表，每项形如 {@code 列名 ASC/DESC}
     * @param limit        返回行数上限，0 或负数表示不限
     * @param offset       跳过行数，0 或负数表示不跳过
     * @param <T>          实体类型
     * @return 完整 SELECT 语句
     */
    static <T> String buildSelectSql(Class<T> entityClass, List<String> selectColumns,
                                      List<JoinClause> joins, String where, String groupBy,
                                      String having, List<String> orderBys, int limit, int offset) {
        StringBuilder statement = new StringBuilder("SELECT ");
        String groupColumns = normalizeColumns(nz(groupBy), entityClass);
        String projection = buildProjection(selectColumns, groupColumns, entityClass);
        statement.append(projection).append(" FROM ").append(tableNameOf(entityClass));
        if (joins != null) {
            for (JoinClause join : joins) {
                if (join == null) {
                    continue;
                }
                statement.append(' ').append(nz(join.joinType())).append(" JOIN ")
                        .append(join.renderTable()).append(" ON ").append(nz(join.onCondition()));
            }
        }
        String whereClause = normalizeColumns(nz(where), entityClass);
        if (!whereClause.isEmpty()) {
            statement.append(" WHERE ").append(whereClause);
        }
        if (!groupColumns.isEmpty()) {
            statement.append(" GROUP BY ").append(groupColumns);
        }
        String havingClause = normalizeColumns(nz(having), entityClass);
        if (!havingClause.isEmpty()) {
            statement.append(" HAVING ").append(havingClause);
        }
        String orderByClause = buildOrderBy(orderBys, entityClass);
        if (!orderByClause.isEmpty()) {
            statement.append(" ORDER BY ").append(orderByClause.substring(2));
        }
        return appendPaging(statement.toString(), limit, offset);
    }

    /**
     * 计算 SELECT 投影片段：显式投影优先；仅有 GROUP BY 时只选分组列，避免非分组列报错。
     *
     * @param selectColumns 显式投影列
     * @param groupColumns  已归一化的分组列
     * @param entityClass   实体类型
     * @return 投影片段，形如 {@code *} 或 {@code host, AVG(cpu_util)}
     */
    static String buildProjection(List<String> selectColumns, String groupColumns, Class<?> entityClass) {
        if (selectColumns != null && !selectColumns.isEmpty()) {
            List<String> normalized = new ArrayList<>(selectColumns.size());
            for (String column : selectColumns) {
                String fragment = normalizeColumns(nz(column), entityClass);
                if (!fragment.isEmpty()) {
                    normalized.add(fragment);
                }
            }
            if (!normalized.isEmpty()) {
                return String.join(", ", normalized);
            }
        }
        return groupColumns.isEmpty() ? "*" : groupColumns;
    }

    /**
     * 渲染 ORDER BY 片段（已归一化列名）。
     *
     * @param orderBys    排序列表，每项形如 {@code 列名 ASC/DESC}
     * @param entityClass 实体类型
     * @return 以 {@code ", "} 开头的片段串，无排序时返回空串
     */
    static String buildOrderBy(List<String> orderBys, Class<?> entityClass) {
        if (orderBys == null || orderBys.isEmpty()) {
            return "";
        }
        StringBuilder orderBy = new StringBuilder();
        for (String ob : orderBys) {
            if (ob == null || ob.isBlank()) {
                continue;
            }
            String[] parts = ob.trim().split("\\s+", 2);
            orderBy.append(", ").append(normalizeColumns(parts[0], entityClass))
                    .append(parts.length > 1 ? " " + parts[1] : "");
        }
        return orderBy.toString();
    }

    /**
     * 追加物理分页子句。
     * <p>GreptimeDB 原生支持 {@code LIMIT n OFFSET m}（见 {@code META-INF/dialect-env/greptime.env}）。
     * 两个值均为已校验的 int，不存在注入面，故直接内联而非占位符，便于服务端复用执行计划。</p>
     * <p>分页语法要求 {@code OFFSET} 必须与 {@code LIMIT} 同现：只给了 offset 时以
     * {@link Long#MAX_VALUE} 充当"不限行数"，避免 offset 被静默丢弃。</p>
     *
     * @param statement 不含分页的 SQL
     * @param limit     返回行数上限，0 或负数表示不限
     * @param offset    跳过行数，负数按 0 处理
     * @return 带分页子句的 SQL
     */
    private static String appendPaging(String statement, int limit, int offset) {
        int safeOffset = Math.max(offset, 0);
        if (limit <= 0) {
            return safeOffset > 0 ? statement + " LIMIT " + Long.MAX_VALUE + " OFFSET " + safeOffset : statement;
        }
        return statement + " LIMIT " + limit + " OFFSET " + safeOffset;
    }

    /**
     * 执行真实查询并映射为实体列表。
     *
     * @param statement   完整 SQL
     * @param params      占位符参数
     * @param entityClass 实体类型
     * @param <T>         实体类型参数
     * @return 实体列表
     * @throws IllegalStateException 查询失败时抛出
     */
    private <T> List<T> runQuery(String statement, List<Object> params, Class<T> entityClass) {
        JdbcResult result = execQuery(statement, params, null);
        Map<String, Field> fields = fieldsOf(entityClass);
        List<T> out = new ArrayList<>(result.rows().size());
        for (Object[] row : result.rows()) {
            out.add(mapRow(entityClass, fields, result.columns(), row));
        }
        return out;
    }

    /**
     * 当前引擎支持数据库物理分页：已配置 GreptimeDB 数据源即成立（MySQL 协议 4002 端口）。
     *
     * @param entityClass 实体类类型
     * @return true 表示走 COUNT + LIMIT/OFFSET 的物理分页
     */
    @Override
    protected boolean supportsNativePaging(Class<?> entityClass) {
        return datasourceOrNull(null) != null;
    }

    /**
     * 统计查询条件命中的总行数，渲染为 {@code SELECT COUNT(*) FROM (原始查询) t_count}。
     * <p>分组/ Having 语义随子查询一并下推，保证分页 total 与当前页数据一致；
     * ORDER BY 与分页子句对计数无意义，故不参与渲染。</p>
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 总行数
     * @throws IllegalStateException 计数查询失败时抛出
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> long executeCount(LambdaQueryWrapper<T> wrapper) {
        if (wrapper == null) {
            throw new IllegalArgumentException("查询条件不能为空");
        }
        QuerySql<T> sql = (QuerySql<T>) wrapper.buildSql();
        Class<T> entityClass = sql.entityClass();
        List<Object> params = new ArrayList<>(sql.params() == null ? List.of() : sql.params());
        if (sql.hasHaving()) {
            params.addAll(sql.havingParams() == null ? List.of() : sql.havingParams());
        }
        String core = buildSelectSql(entityClass, sql.selectColumns(), sql.joins(), sql.whereClause(),
                sql.groupByColumn(), sql.havingClause(), List.of(), 0, 0);
        String countSql = "SELECT COUNT(*) FROM (" + core + ") t_count";
        JdbcResult result = execQuery(countSql, params, null);
        List<Object[]> rows = result.rows();
        if (rows.isEmpty() || rows.get(0).length == 0) {
            return 0L;
        }
        Object total = rows.get(0)[0];
        if (total == null) {
            return 0L;
        }
        Number converted = Converter.convertIfNecessary(total, Number.class);
        return converted == null ? Long.parseLong(total.toString().trim()) : converted.longValue();
    }

    // ==================== 列名归一化 ====================

    /**
     * 归一化表达式中的列名。
     * <p>Lambda 解析器可能输出驼峰（cpuUtil）或去下划线小写（cpuutil），
     * 需映射回实体声明的真实 snake_大小写 列名。</p>
     *
     * @param expr        待归一化的 SQL 片段
     * @param entityClass 实体类
     * @return normalizeColumns的结果
     */
    static String normalizeColumns(String expr, Class<?> entityClass) {
        if (expr == null || expr.isEmpty()) {
            return expr == null ? "" : expr;
        }
        List<String[]> rules = columnAliasRules(entityClass);
        String out = expr;
        for (String[] rule : rules) {
            out = out.replaceAll("(?<![\\w])" + Pattern.quote(rule[0]) + "(?![\\w])",
                    Matcher.quoteReplacement(rule[1]));
        }
        return out;
    }

    /**
     * 构建实体字段到真实列名的别名替换规则（长变体优先）。
     *
     * @param entityClass 实体类型
     * @return 规则列表，元素为 [变体, 真实列名]
     */
    static List<String[]> columnAliasRules(Class<?> entityClass) {
        Map<String, Field> fields = fieldsOf(entityClass);
        List<String[]> rules = new ArrayList<>();
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            String canonical = e.getKey();
            addRule(rules, e.getValue().getName(), canonical);
            addRule(rules, e.getValue().getName().toLowerCase(), canonical);
            addRule(rules, canonical.replace("_", ""), canonical);
        }
        rules.sort((a, b) -> b[0].length() - a[0].length());
        return rules;
    }

    /**
     * 追加一条别名规则，去重且忽略与真实列名相同的变体。
     *
     * @param rules     规则容器
     * @param variant   变体名
     * @param canonical 真实列名
     */
    static void addRule(List<String[]> rules, String variant, String canonical) {
        if (variant != null && !variant.isEmpty() && !variant.equals(canonical)
                && rules.stream().noneMatch(r -> r[0].equals(variant))) {
            rules.add(new String[]{variant, canonical});
        }
    }

    // ==================== 通用 SQL 执行器 ====================

    /**
     * 获取默认数据源的真实 SQL 执行器（MySQL 协议 JDBC）。
     *
     * @return SQL 执行器；未配置数据源时返回 空
     */
    @Deprecated
    @Override
    public SqlExecutor getExecutor() {
        return getExecutor(getDefaultDataSourceName());
    }

    /**
     * 获取指定数据源的真实 SQL 执行器（MySQL 协议 JDBC）。
     * <p>返回的执行器与传入名称绑定，后续 SQL 只会路由到该数据源，不再静默串到默认数据源。</p>
     *
     * @param n 数据源名称，为 空 时取默认数据源
     * @return SQL 执行器；数据源不存在时返回 空
     */
    @Deprecated
    @Override
    public SqlExecutor getExecutor(String n) {
        GreptimeDbEngineDataSource ds = datasourceOrNull(n);
        return ds == null ? null : new JdbcExecutorAdapter(ds.name());
    }

    /**
     * 基于 MySQL 协议 JDBC 连接的通用 SQL 执行器适配器。
     * <p>与构造时传入的数据源名称绑定，实现多数据源下的精确路由。</p>
     *
     * @author CH
     * @since 4.0.0
     */
    class JdbcExecutorAdapter implements SqlExecutor {

        /**
         * 绑定的数据源名称
         */
        private final String dataSourceName;

        /**
         * 构造执行器适配器。
         *
         * @param dataSourceName 绑定的数据源名称，不可为 空
         */
        JdbcExecutorAdapter(String dataSourceName) {
            this.dataSourceName = dataSourceName;
        }

        /**
         * 查询并返回行映射列表。
         *
         * @param sql    含 {@code ?} 占位符的 SQL，不可为 空
         * @param params 参数
         * @return 行列表，键为列名
         */
        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            JdbcResult r = execQuery(requireSql(sql), toParamList(params), dataSourceName);
            List<Map<String, Object>> out = new ArrayList<>(r.rows().size());
            for (Object[] row : r.rows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < r.columns().size() && i < row.length; i++) {
                    m.put(r.columns().get(i), row[i]);
                }
                out.add(m);
            }
            return out;
        }

        /**
         * 查询并映射为实体列表。
         *
         * @param sql         含 {@code ?} 占位符的 SQL，不可为 空
         * @param entityClass 实体类型，不可为 空
         * @param params      参数
         * @param <T>         实体类型参数
         * @return 实体列表
         */
        @Override
        public <T> List<T> query(String sql, Class<T> entityClass, Object... params) {
            if (entityClass == null) {
                throw new IllegalArgumentException("实体类型不能为空");
            }
            JdbcResult r = execQuery(requireSql(sql), toParamList(params), dataSourceName);
            Map<String, Field> fields = fieldsOf(entityClass);
            List<T> out = new ArrayList<>(r.rows().size());
            for (Object[] row : r.rows()) {
                out.add(mapRow(entityClass, fields, r.columns(), row));
            }
            return out;
        }

        /**
         * 物理分页查询（LIMIT/OFFSET 下推服务端）。
         *
         * @param sql     不含分页后缀的 SQL，不可为 空
         * @param page    分页参数，不可为 空
         * @param params  参数
         * @return 行映射列表
         */
        @Override
        public List<Map<String, Object>> queryPage(String sql, Pagination page, Object... params) {
            if (page == null) {
                throw new IllegalArgumentException("分页参数不能为空");
            }
            String paged = appendPaging(requireSql(sql), page.getLimit(), page.getOffset());
            return query(paged, params);
        }

        /**
         * 执行 DML/DDL。
         *
         * @param sql    SQL，不可为 空
         * @param params 参数
         * @return 服务端报告的影响行数
         */
        @Override
        public int execute(String sql, Object... params) {
            String statement = requireSql(sql);
            try {
                return jdbcFor(datasource(dataSourceName)).update(statement, toParamList(params));
            } catch (SQLException e) {
                throw new IllegalStateException("GreptimeDB 执行失败: " + statement, e);
            }
        }

        /**
         * 批量执行同构 DML。
         *
         * @param sql       SQL，不可为 空
         * @param paramList 每行参数
         * @return 各行影响行数
         */
        @Override
        public int[] batch(String sql, List<Object[]> paramList) {
            String statement = requireSql(sql);
            try {
                return jdbcFor(datasource(dataSourceName)).batch(statement, paramList);
            } catch (SQLException e) {
                throw new IllegalStateException("GreptimeDB 批量执行失败: " + statement, e);
            }
        }
    }

    /**
     * 统一查询入口：异常包装为运行时异常。
     *
     * @param sql            完整 SQL
     * @param params         占位符参数
     * @param dataSourceName 数据源名称，可为 空（取默认数据源）
     * @return 执行查询的结果
     * @throws IllegalStateException 查询失败时抛出
     */
    private JdbcResult execQuery(String sql, List<Object> params, String dataSourceName) {
        try {
            return jdbcFor(datasource(dataSourceName)).query(sql, params);
        } catch (SQLException e) {
            throw new IllegalStateException("GreptimeDB 查询失败: " + sql, e);
        }
    }

    /**
     * 校验 SQL 语句非空。
     *
     * @param sql 待校验 SQL
     * @return 去除首尾空白后的 SQL
     * @throws IllegalArgumentException SQL 为空时抛出
     */
    private static String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 语句不能为空");
        }
        return sql.trim();
    }

    /**
     * 可变参数转列表，兼容 空 数组。
     *
     * @param params 参数数组
     * @return 参数列表，非 空
     */
    private static List<Object> toParamList(Object... params) {
        return params == null || params.length == 0 ? List.of() : Arrays.asList(params);
    }

    // ==================== 删除（MySQL 协议 JDBC，真库） ====================

    /**
     * 执行真实删除：WHERE 下推服务端，返回服务端报告的真实影响行数。
     *
     * @param sql 删除 SQL 信息
     * @param <T> 实体类型
     * @return 影响行数
     * @throws IllegalStateException    缺少 WHERE 条件或删除失败时抛出
     * @throws IllegalArgumentException 表名/实体非法时抛出
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        if (sql == null) {
            throw new IllegalArgumentException("删除条件不能为空");
        }
        String where = nz(sql.whereClause());
        if (where.isEmpty()) {
            throw new IllegalStateException("DELETE 必须携带 WHERE 条件（时序库删除需命中 tag/time 列）");
        }
        String table = tableNameOf(sql.entityClass());
        String deleteSql = "DELETE FROM " + table + " WHERE "
                + normalizeColumns(where, sql.entityClass());
        try {
            List<Object> params = sql.params() == null ? List.of() : sql.params();
            // 返回服务端报告的真实影响行数
            return jdbcFor(datasource()).update(deleteSql, params);
        } catch (SQLException e) {
            throw new IllegalStateException("GreptimeDB 删除失败: " + deleteSql, e);
        }
    }

    // ==================== 明确不支持的时序库语义 ====================

    /**
     * greptimedb 为时序库，不存在 更新 语句：
     * 相同 标签 + 时间戳再次 插入 即为整行覆盖（upsert）。
     *
     * @param sql 更新 SQL 信息
     * @param <T> 实体类型
     * @return 永不返回
     * @throws UnsupportedOperationException 恒定抛出，提示改用写入覆盖
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "GreptimeDB 为时序库，不支持 UPDATE。"
                        + "请以相同 tag + 时间戳重新 write() 实现覆盖(upsert)。");
    }

    /**
     * 引擎数据一律经 {@link #write} 真实落库，禁止内存旁路存储：
     * 内存存储会造成"看似写入成功、进程退出即丢失"的假持久化。
     *
     * @param name 存储名称
     * @param data 数据列表
     * @param <T>  数据类型
     * @return 永不返回
     * @throws UnsupportedOperationException 恒定抛出，提示改用真实写入通道
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException(
                "GreptimeDbEngine 不支持内存存储，请使用 write(Table) 或 getExecutor().execute(INSERT ...) 写入真库。");
    }

    // ==================== 行映射 ====================

    /**
     * 获取实体全部字段并建立 snake_大小写 列名映射（含父类字段，结果缓存）。
     *
     * @param clazz 实体类型
     * @return 列名 -> 字段 映射
     */
    private static Map<String, Field> fieldsOf(Class<?> clazz) {
        if (clazz == null) {
            return Collections.emptyMap();
        }
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new ConcurrentHashMap<>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.isSynthetic()) {
                        continue;
                    }
                    map.putIfAbsent(camelToSnake(f.getName()), f);
                }
            }
            return map;
        });
    }

    /**
     * 驼峰命名转 snake_大小写 列名。
     *
     * @param name 驼峰属性名
     * @return snake_case 列名
     */
    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                sb.append('_').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 将一行结果集映射为实体：按列标签匹配字段并完成类型转换。
     *
     * @param clazz   实体类型
     * @param fields  列名 -> 字段 映射
     * @param columns 结果集列标签
     * @param row     行数据
     * @param <T>     实体类型
     * @return 实体实例
     * @throws IllegalStateException 实体无法实例化时抛出
     */
    private static <T> T mapRow(Class<T> clazz, Map<String, Field> fields,
                                List<String> columns, Object[] row) {
        T instance = ReflectUtils.instantiate(clazz);
        if (instance == null) {
            throw new IllegalStateException("GreptimeDB 行映射失败: " + clazz.getName()
                    + " 需要无参构造器");
        }
        for (int i = 0; i < columns.size() && i < row.length; i++) {
            String label = columns.get(i);
            Field field = findField(fields, label);
            if (field == null || row[i] == null) {
                continue;
            }
            if (!ReflectUtils.setField(instance, field.getName(), convert(row[i], field.getType()))) {
                // 读查询允许降级：单列写入失败不阻断整行返回，但必须留下痕迹便于排查
                log.debug("GreptimeDB 列映射失败，已跳过: class={}, column={}, field={}",
                        clazz.getName(), label, field.getName());
            }
        }
        return instance;
    }

    /**
     * 按列标签查找实体字段：精确 → 全小写 → 忽略大小写与下划线的宽松匹配。
     * <p>GreptimeDB 经 MySQL 协议回传的列标签大小写随建表语句而定，
     * 只做精确匹配会静默丢列，故补充宽松匹配。</p>
     *
     * @param fields 列名 -> 字段 映射
     * @param column 结果集列标签
     * @return 匹配字段，未匹配返回 空
     */
    private static Field findField(Map<String, Field> fields, String column) {
        if (column == null || column.isEmpty()) {
            return null;
        }
        Field exact = fields.get(column);
        if (exact != null) {
            return exact;
        }
        String target = stripUnderscoreLower(column);
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            if (stripUnderscoreLower(entry.getKey()).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 列名宽松比较键：去下划线 + 转小写。
     *
     * @param name 列名或字段名
     * @return 比较键
     */
    private static String stripUnderscoreLower(String name) {
        return name == null ? "" : name.replace("_", "").toLowerCase();
    }

    /**
     * 按目标字段类型转换数据库取值（数值/布尔/字符串/时间戳互转）。
     *
     * @param v    数据库原始值，非 空
     * @param type 目标字段类型
     * @return 转换后的值；无法按目标类型转换时回退为 空 处理，交由字段设置环节判定
     */
    private static Object convert(Object v, Class<?> type) {
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(v, type);
        if (converted != null) {
            return converted;
        }
        // 转换器无匹配实现时：仅当目标类型可容纳字符串才回退 toString，
        // 否则原样返回，避免把 "1.5" 之类塞进数值字段导致静默错位
        if (type == String.class || type == CharSequence.class || type == Object.class) {
            return type == Object.class ? v : v.toString();
        }
        return v;
    }

    // ==================== 连接信息推导与生命周期 ====================

    /**
     * 获取指定数据源的数据库名，缺省 {@code public}。
     *
     * @param ds 数据源封装
     * @return 数据库名
     */
    private static String databaseOf(GreptimeDbEngineDataSource ds) {
        String db = ds.database();
        return db == null || db.isEmpty() ? DEFAULT_DATABASE : db;
    }

    /**
     * 获取（或按配置指纹重建）指定数据源专用的 JDBC 客户端。
     * <p>缓存以数据源名称为键，避免多数据源场景下查询串库。</p>
     *
     * @param ds 数据源封装，不可为 空
     * @return JDBC 客户端
     */
    private GreptimeJdbcClient jdbcFor(GreptimeDbEngineDataSource ds) {
        if (ds == null) {
            throw new IllegalStateException("未配置 GreptimeDB 数据源");
        }
        String key = ds.name() == null ? "" : ds.name();
        String url = resolveJdbcUrl(ds);
        String fingerprint = url + "|" + nz(ds.username()) + "|" + nz(ds.password());
        synchronized (jdbcClients) {
            JdbcHolder holder = jdbcClients.get(key);
            if (holder == null || !holder.fingerprint.equals(fingerprint)) {
                if (holder != null) {
                    holder.client.close();
                }
                holder = new JdbcHolder(fingerprint,
                        new GreptimeJdbcClient(url, ds.username(), ds.password()));
                jdbcClients.put(key, holder);
            }
            return holder.client;
        }
    }

    /**
     * 推导 JDBC URL：优先显式覆盖值；否则取 gRPC 端点主机 + 默认 4002 端口。
     *
     * @param ds 数据源封装
     * @return resolveJdbcUrl的结果
     * @throws IllegalStateException 既无覆盖地址又无端点信息时抛出
     */
    private String resolveJdbcUrl(GreptimeDbEngineDataSource ds) {
        String overrideValue = jdbcUrlOverride;
        if (overrideValue != null && !overrideValue.isEmpty()) {
            return overrideValue;
        }
        String endpoint = ds.url();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalStateException("数据源 " + ds.name()
                    + " 缺少端点信息，请调用 setJdbcUrl 指定 JDBC 地址");
        }
        return GreptimeJdbcClient.jdbcUrlFromEndpoint(endpoint, databaseOf(ds));
    }

    /**
     * 解析并校验实体对应的表名。
     *
     * @param entityClass 实体类型
     * @return 通过白名单校验的表名
     * @throws IllegalArgumentException 实体为空或表名含非法字符时抛出
     */
    static String tableNameOf(Class<?> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("实体类不能为空，无法解析 GreptimeDB 表名");
        }
        return safeIdentifier(resolveTableName(entityClass));
    }

    /**
     * 校验并返回安全的 SQL 标识符（字符集规则见 {@link SqlName#isWord(String)}）。
     *
     * @param id 待校验标识符
     * @return 去除首尾空白后的标识符
     * @throws IllegalArgumentException 标识符非法时抛出
     */
    static String safeIdentifier(String id) {
        if (id == null) {
            throw new IllegalArgumentException("SQL 标识符不能为空");
        }
        String trimmed = id.trim();
        if (!SqlName.isWord(trimmed)) {
            throw new IllegalArgumentException("非法的 SQL 标识符: " + id);
        }
        return trimmed;
    }

    /**
     * 字符串 空 值兜底并去首尾空白。
     *
     * @param value 原值
     * @return 非 空 字符串
     */
    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 关闭引擎：释放 gRPC 数据源与全部 JDBC 连接资源，并置关闭标志阻止后续取用。
     */
    @Override
    public void close() {
        this.closed = true;
        super.close();
        synchronized (jdbcClients) {
            for (JdbcHolder holder : jdbcClients.values()) {
                holder.client.close();
            }
            jdbcClients.clear();
        }
    }
}
