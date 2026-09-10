package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * GreptimeDB 时序数据库引擎实现。
 * <p>
 * <b>写入</b>走官方 gRPC Ingester SDK（{@link #write} / 流式 / Bulk）；
 * <b>查询与删除</b>按官方文档推荐，经 <b>MySQL 协议(默认 4002 端口) JDBC 驱动</b>
 * 执行真实参数化 SQL。通过 SPI 注册为 {@code "greptimedb"}。
 * </p>
 * <p>
 * 时序库语义说明：
 * <ul>
 *   <li>不支持 {@code UPDATE} —— 相同 tag + 时间戳重复写入即为覆盖(upsert)，
 *       见 {@link #executeUpdate}</li>
 *   <li>{@code DELETE} 要求 WHERE 条件命中 tag/time 列（服务端校验），返回真实影响行数</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("greptimedb")
public class GreptimeDbEngine extends AbstractEngine {

    /**
     * 默认 MySQL 协议端口（GreptimeDB 约定：4000=HTTP，4001=gRPC，4002=MySQL）
     */
    private static final int DEFAULT_MYSQL_PORT = 4002;

    /**
     * 缺省数据库名
     */
    private static final String DEFAULT_DATABASE = "public";

    /**
     * 实体字段映射缓存：类 -> (snake_case 列名 -> Field)
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 安全 SQL 标识符校验规则（仅字母 / 数字 / 下划线）
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * 覆盖用的 JDBC URL；为空时按 gRPC 端点主机 + 4002 端口推导。
     */
    private volatile String jdbcUrlOverride;

    /**
     * JDBC 客户端缓存及其构建参数指纹（变更时自动重建）。
     */
    private volatile GreptimeJdbcClient jdbcClient;
    private volatile String jdbcClientFingerprint;

    /**
     * 覆盖默认 JDBC URL（非标准端口或开启 TLS 时使用）。
     *
     * @param jdbcUrl 形如 {@code jdbc:mysql://host:4002/public?useSSL=false}
     * @return this
     */
    public GreptimeDbEngine setJdbcUrl(String jdbcUrl) {
        this.jdbcUrlOverride = jdbcUrl;
        this.jdbcClient = null;
        return this;
    }

    // ==================== 写入（gRPC 官方 SDK） ====================

    /**
     * 写入一张表到 GreptimeDB（gRPC 异步写入）。
     *
     * @param table 表数据（由 SDK {@link Table} 构建）
     * @return 写入结果 Future
     */
    public CompletableFuture<Result<WriteOk, Err>> write(Table table) {
        return client().write(table);
    }

    /**
     * 批量写入多张表到 GreptimeDB（gRPC 异步写入）。
     *
     * @param tables 表数据
     * @return 写入结果 Future
     */
    public CompletableFuture<Result<WriteOk, Err>> write(Table... tables) {
        return client().write(tables);
    }

    /**
     * 获取默认 GreptimeDB gRPC 客户端。
     * <p>若默认数据源缺失（如引擎被复用后状态残留），回退到任意已注册的 GreptimeDB 数据源。</p>
     *
     * @return GreptimeDB 客户端
     */
    public GreptimeDB client() {
        return datasource().getSource();
    }

    private GreptimeDbEngineDataSource datasource() {
        EngineDataSource<Object> ds = null;
        if (defaultDataSourceName != null) {
            ds = dataSources.get(defaultDataSourceName);
        }
        if (ds == null && !dataSources.isEmpty()) {
            ds = dataSources.values().iterator().next();
        }
        Object raw = ds;
        if (!(raw instanceof GreptimeDbEngineDataSource gdb)) {
            throw new IllegalStateException("未配置 GreptimeDB 数据源");
        }
        return gdb;
    }

    // ==================== 数据源注册 ====================

    /**
     * 添加数据源（GreptimeDB 客户端或连接串）。
     *
     * @param name       数据源名称
     * @param dataSource 数据源封装
     * @param <T>        底层类型
     * @return this
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
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
            throw new IllegalArgumentException("GreptimeDbEngine 仅支持 GreptimeDB 客户端或 URL 字符串");
        }
        dataSources.put(name, (EngineDataSource<Object>) (Object) wrapped);
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源（直接创建客户端）。
     *
     * @param name      数据源名称
     * @param endpoint  GreptimeDB gRPC 端点
     * @param database  数据库名
     * @param username  用户名（为空表示无鉴权）
     * @param password  密码
     * @return this
     */
    public GreptimeDbEngine addDataSource(String name, String endpoint,
                                          String database, String username, String password) {
        GreptimeDB grpcClient = GreptimeDbClient.create(endpoint, database, username, password);
        GreptimeDbEngineDataSource wrapped = new GreptimeDbEngineDataSource(
                name, endpoint, username, password, database, grpcClient);
        dataSources.put(name, (EngineDataSource<Object>) (Object) wrapped);
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    // ==================== 查询（MySQL 协议 JDBC，真库） ====================

    /**
     * 覆盖父类：WHERE / ORDER BY 全部下推为真实参数化 SQL，不做内存重排。
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> List<T> executeQuery(com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper<T> wrapper,
                                    Class<T> entityClass) {
        com.chua.common.support.lang.datasource.engine.wrapper.QuerySql<T> sql =
                (com.chua.common.support.lang.datasource.engine.wrapper.QuerySql<T>) wrapper.buildSql();
        String where = sql.whereClause() == null ? "" : sql.whereClause().trim();
        StringBuilder orderBy = new StringBuilder();
        for (String ob : sql.orderBys()) {
            String[] parts = ob.trim().split("\\s+", 2);
            orderBy.append(orderBy.length() == 0 ? " ORDER BY " : ", ")
                    .append(normalizeColumns(parts[0], entityClass))
                    .append(parts.length > 1 ? " " + parts[1] : "");
        }
        return doQuery(entityClass, normalizeColumns(where, entityClass),
                sql.params(), orderBy.toString());
    }

    /**
     * 父类抽象方法实现：将 WHERE 条件下推为真实 SQL 查询。
     *
     * @param where       含 {@code ?} 占位符的条件子句（已归一化列名）
     * @param params      占位符参数
     * @param entityClass 实体类型
     * @param <T>         实体类型参数
     * @return 实体列表
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        return doQuery(entityClass, normalizeColumns(where == null ? "" : where.trim(), entityClass),
                params == null ? List.of() : Arrays.asList(params), "");
    }

    /**
     * 执行真实查询并映射为实体列表。
     *
     * @param entityClass 实体类型
     * @param where       已归一化的条件子句，可为空串
     * @param params      占位符参数
     * @param orderBy     ORDER BY 子句，可为空串
     * @param <T>         实体类型参数
     * @return 实体列表
     */
    private <T> List<T> doQuery(Class<T> entityClass, String where, List<Object> params, String orderBy) {
        String table = getTableName(entityClass);
        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ").append(table);
        if (!where.isEmpty()) {
            sqlBuilder.append(" WHERE ").append(where);
        }
        sqlBuilder.append(orderBy);

        JdbcResult result;
        try {
            result = jdbc().query(sqlBuilder.toString(), params);
        } catch (SQLException e) {
            throw new IllegalStateException("GreptimeDB 查询失败: " + sqlBuilder, e);
        }

        Map<String, Field> fields = fieldsOf(entityClass);
        List<T> out = new ArrayList<>(result.rows().size());
        for (Object[] row : result.rows()) {
            out.add(mapRow(entityClass, fields, result.columns(), row));
        }
        return out;
    }

    // ==================== 列名归一化 ====================

    /**
     * 归一化表达式中的列名。
     * <p>Lambda 解析器可能输出驼峰（cpuUtil）或去下划线小写（cpuutil），
     * 需映射回实体声明的真实 snake_case 列名。</p>
     */
    private String normalizeColumns(String expr, Class<?> entityClass) {
        if (expr == null || expr.isEmpty()) {
            return expr;
        }
        List<String[]> rules = columnAliasRules(entityClass);
        String out = expr;
        for (String[] rule : rules) {
            out = out.replaceAll("(?<![\\w])" + java.util.regex.Pattern.quote(rule[0]) + "(?![\\w])",
                    java.util.regex.Matcher.quoteReplacement(rule[1]));
        }
        return out;
    }

    /**
     * 构建实体字段到真实列名的别名替换规则（长变体优先）。
     *
     * @param entityClass 实体类型
     * @return 规则列表，元素为 [变体, 真实列名]
     */
    private List<String[]> columnAliasRules(Class<?> entityClass) {
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
    private void addRule(List<String[]> rules, String variant, String canonical) {
        if (variant != null && !variant.isEmpty() && !variant.equals(canonical)
                && rules.stream().noneMatch(r -> r[0].equals(variant))) {
            rules.add(new String[]{variant, canonical});
        }
    }

    // ==================== 通用 SQL 执行器 ====================

    /**
     * 获取默认数据源的真实 SQL 执行器（MySQL 协议 JDBC）。
     *
     * @return SQL 执行器；未配置数据源时返回 null
     */
    @Override
    public com.chua.common.support.lang.datasource.engine.executor.SqlExecutor getExecutor() {
        return getExecutor(getDefaultDataSourceName());
    }

    /**
     * 获取指定数据源的真实 SQL 执行器（MySQL 协议 JDBC）。
     *
     * @param n 数据源名称
     * @return SQL 执行器；数据源不存在时返回 null
     */
    @Override
    public com.chua.common.support.lang.datasource.engine.executor.SqlExecutor getExecutor(String n) {
        EngineDataSource<Object> ds = n == null ? null : dataSources.get(n);
        if (ds == null && !dataSources.isEmpty()) {
            ds = dataSources.values().iterator().next();
        }
        return ds == null ? null : new JdbcExecutorAdapter();
    }

    /**
     * 基于 MySQL 协议 JDBC 连接的通用 SQL 执行器适配器。
     */
    class JdbcExecutorAdapter implements com.chua.common.support.lang.datasource.engine.executor.SqlExecutor {

        /**
         * 查询并返回行映射列表。
         *
         * @param sql    含 {@code ?} 占位符的 SQL
         * @param params 参数
         * @return 行列表，键为列名
         */
        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            JdbcResult r = execQuery(sql, params);
            List<Map<String, Object>> out = new ArrayList<>(r.rows().size());
            for (Object[] row : r.rows()) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
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
         * @param sql         含 {@code ?} 占位符的 SQL
         * @param entityClass 实体类型
         * @param params      参数
         * @param <T>         实体类型参数
         * @return 实体列表
         */
        @Override
        public <T> List<T> query(String sql, Class<T> entityClass, Object... params) {
            JdbcResult r = execQuery(sql, params);
            Map<String, Field> fields = fieldsOf(entityClass);
            List<T> out = new ArrayList<>(r.rows().size());
            for (Object[] row : r.rows()) {
                out.add(mapRow(entityClass, fields, r.columns(), row));
            }
            return out;
        }

        /**
         * 物理分页查询（LIMIT/OFFSET 下推）。
         *
         * @param sql     不含分页后缀的 SQL
         * @param page    分页参数
         * @param params  参数
         * @return 行映射列表
         */
        @Override
        public List<Map<String, Object>> queryPage(String sql,
                com.chua.common.support.lang.datasource.dialect.Pagination page, Object... params) {
            String paged = sql + " LIMIT ? OFFSET ?";
            List<Object> ps2 = new ArrayList<>(List.of(params == null ? new Object[0] : params));
            ps2.add(page.getLimit());
            ps2.add(Math.max(page.getOffset(), 0));
            return query(paged, ps2.toArray());
        }

        /**
         * 执行 DML/DDL。
         *
         * @param sql    SQL
         * @param params 参数
         * @return 影响行数
         */
        @Override
        public int execute(String sql, Object... params) {
            try {
                return jdbc().update(sql, params == null ? List.of() : Arrays.asList(params));
            } catch (SQLException e) {
                throw new IllegalStateException("GreptimeDB 执行失败: " + sql, e);
            }
        }

        /**
         * 批量执行同构 DML。
         *
         * @param sql       SQL
         * @param paramList 每行参数
         * @return 各行影响行数
         */
        @Override
        public int[] batch(String sql, List<Object[]> paramList) {
            try {
                return jdbc().batch(sql, paramList);
            } catch (SQLException e) {
                throw new IllegalStateException("GreptimeDB 批量执行失败: " + sql, e);
            }
        }

        /**
         * 统一查询入口：异常包装为运行时异常。
         */
        private JdbcResult execQuery(String sql, Object[] params) {
            try {
                return jdbc().query(sql, params == null ? List.of() : Arrays.asList(params));
            } catch (SQLException e) {
                throw new IllegalStateException("GreptimeDB 查询失败: " + sql, e);
            }
        }
    }

    // ==================== 删除（MySQL 协议 JDBC，真库） ====================

    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        String where = sql.whereClause();
        if (where == null || where.isBlank()) {
            throw new IllegalStateException("DELETE 必须携带 WHERE 条件（时序库删除需命中 tag/time 列）");
        }
        String table = safeIdentifier(getTableName(sql.entityClass()));
        // 豁免：表名由实体类名派生(getTableName)，WHERE 由框架 LambdaQueryWrapper 解析生成，params 经 jdbc().update() 参数化传递
        String deleteSql = "DELETE FROM " + table + " WHERE " + normalizeColumns(where, sql.entityClass());
        try {
            // 返回服务端报告的真实影响行数
            return jdbc().update(deleteSql, sql.params());
        } catch (SQLException e) {
            throw new IllegalStateException("GreptimeDB 删除失败: " + deleteSql, e);
        }
    }

    // ==================== 明确不支持的时序库语义 ====================

    /**
     * GreptimeDB 为时序库，不存在 UPDATE 语句：
     * 相同 tag + 时间戳再次 INSERT 即为整行覆盖（upsert）。
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "GreptimeDB 为时序库，不支持 UPDATE。"
                        + "请以相同 tag + 时间戳重新 write() 实现覆盖(upsert)。");
    }

    /**
     * 引擎数据一律经 {@link #write} 真实落库，禁止内存旁路存储。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException(
                "GreptimeDbEngine 不支持内存存储，请使用 write(Table) 写入真库。");
    }

    // ==================== 行映射 ====================

    /**
     * 获取实体全部字段并建立 snake_case 列名映射（含父类字段，结果缓存）。
     *
     * @param clazz 实体类型
     * @return 列名 -> Field 映射
     */
    private static Map<String, Field> fieldsOf(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new ConcurrentHashMap<>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    map.putIfAbsent(camelToSnake(f.getName()), f);
                }
            }
            return map;
        });
    }

    /**
     * 驼峰命名转 snake_case 列名。
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

    @SuppressWarnings("unchecked")
    private static <T> T mapRow(Class<T> clazz, Map<String, Field> fields,
                                List<String> columns, Object[] row) {
        try {
            T instance = ReflectUtils.instantiate(clazz);
            for (int i = 0; i < columns.size() && i < row.length; i++) {
                Field field = fields.get(columns.get(i));
                if (field == null || row[i] == null) {
                    continue;
                }
                field.setAccessible(true);
                field.set(instance, convert(row[i], field.getType()));
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GreptimeDB 行映射失败: " + clazz.getName()
                    + " 需要无参构造器", e);
        }
    }

    /**
     * 按目标字段类型转换数据库取值（数值/布尔/字符串/时间戳互转）。
     *
     * @param v    数据库原始值，非 null
     * @param type 目标字段类型
     * @return 转换后的值
     */
    private static Object convert(Object v, Class<?> type) {
        long asLong = Long.MIN_VALUE;
        double asDouble = Double.NaN;
        boolean numericResolved = false;

        if (v instanceof Number num) {
            asLong = num.longValue();
            asDouble = num.doubleValue();
            numericResolved = true;
        } else if (v instanceof java.util.Date || v instanceof LocalDateTime
                || v instanceof OffsetDateTime || v instanceof Instant) {
            asLong = toEpochMilli(v);
            numericResolved = true;
        }

        if (type == String.class) {
            return v instanceof byte[] b ? new String(b) : v.toString();
        }
        if (type == boolean.class || type == Boolean.class) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
        }
        if (!numericResolved) {
            String s = v.toString().trim();
            BigDecimal bd = null;
            try {
                bd = new BigDecimal(s);
            } catch (NumberFormatException ignored) {
                asLong = toEpochMilli(s);
            }
            if (bd != null) {
                asLong = bd.longValue();
                asDouble = bd.doubleValue();
            }
        }
        if (type == int.class || type == Integer.class) {
            return (int) asLong;
        }
        if (type == long.class || type == Long.class) {
            return asLong;
        }
        if (type == double.class || type == Double.class) {
            return asDouble;
        }
        if (type == float.class || type == Float.class) {
            return (float) asDouble;
        }
        if (type == short.class || type == Short.class) {
            return (short) asLong;
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) asLong;
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(v.toString());
        }
        if (type == java.util.Date.class) {
            return new java.util.Date(asLong);
        }
        if (type == Timestamp.class) {
            return new Timestamp(asLong);
        }
        return v.toString();
    }

    private static final List<DateTimeFormatter> TS_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_INSTANT
    );

    /**
     * 将任意时间表示（数值/日期时间/多格式字符串）统一转为 epoch 毫秒。
     *
     * @param v 时间值
     * @return epoch 毫秒数
     */
    private static long toEpochMilli(Object v) {
        if (v instanceof Timestamp t) {
            return t.getTime();
        }
        if (v instanceof java.util.Date d) {
            return d.getTime();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant().toEpochMilli();
        }
        if (v instanceof Instant inst) {
            return inst.toEpochMilli();
        }
        String s = v.toString().trim();
        if (s.matches("-?\\d+")) {
            return Long.parseLong(s);
        }
        for (DateTimeFormatter f : TS_FORMATTERS) {
            try {
                if (f == DateTimeFormatter.ISO_INSTANT || f == DateTimeFormatter.ISO_OFFSET_DATE_TIME) {
                    return Instant.parse(s).toEpochMilli();
                }
                return LocalDateTime.parse(s, f).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // 尝试下一格式
            }
        }
        throw new IllegalArgumentException("无法解析时间戳: " + s);
    }

    // ==================== 连接信息推导与生命周期 ====================

    /**
     * 获取当前数据源数据库名，缺省 {@code public}。
     *
     * @return 数据库名
     */
    private String database() {
        String db = datasource().database();
        return db == null || db.isEmpty() ? DEFAULT_DATABASE : db;
    }

    /**
     * 获取（或按配置指纹重建）查询/删除共用的 JDBC 客户端。
     *
     * @return JDBC 客户端
     */
    private synchronized GreptimeJdbcClient jdbc() {
        String fingerprint = resolveJdbcUrl()
                + "|" + Objects.requireNonNullElse(datasource().username(), "")
                + "|" + Objects.requireNonNullElse(datasource().password(), "");
        GreptimeJdbcClient client = this.jdbcClient;
        if (client == null || !fingerprint.equals(this.jdbcClientFingerprint)) {
            if (client != null) {
                client.close();
            }
            client = new GreptimeJdbcClient(resolveJdbcUrl(),
                    datasource().username(), datasource().password());
            this.jdbcClient = client;
            this.jdbcClientFingerprint = fingerprint;
        }
        return client;
    }

    /**
     * 推导 JDBC URL：优先显式覆盖值；否则取 gRPC 端点主机 + 默认 4002 端口。
     */
    private String resolveJdbcUrl() {
        String overrideValue = jdbcUrlOverride;
        if (overrideValue != null && !overrideValue.isEmpty()) {
            return overrideValue;
        }
        String endpoint = datasource().url();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalStateException("数据源缺少端点信息，请调用 setJdbcUrl 指定 JDBC 地址");
        }
        return GreptimeJdbcClient.jdbcUrlFromEndpoint(endpoint, database());
    }

    /**
     * 校验并返回安全的 SQL 标识符（仅允许字母、数字、下划线）。
     *
     * @param id 待校验标识符
     * @return 去除首尾空白后的标识符
     * @throws IllegalArgumentException 标识符非法时抛出
     */
    private String safeIdentifier(String id) {
        if (id == null) {
            throw new IllegalArgumentException("SQL 标识符不能为空");
        }
        String trimmed = id.trim();
        if (!SAFE_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("非法的 SQL 标识符: " + id);
        }
        return trimmed;
    }

    /**
     * 关闭引擎：释放数据源与 JDBC 连接资源。
     */
    @Override
    public void close() {
        super.close();
        GreptimeJdbcClient client = this.jdbcClient;
        this.jdbcClient = null;
        if (client != null) {
            client.close();
        }
    }
}
