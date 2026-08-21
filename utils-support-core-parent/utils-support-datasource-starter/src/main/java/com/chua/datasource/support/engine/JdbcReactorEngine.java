package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import io.r2dbc.spi.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC 统一响应式引擎，根据数据源数量和类型自动选择执行路径。
 *
 * <p><b>单数据源模式：</b>直接通过 R2DBC 连接工厂执行，真正非阻塞。</p>
 * <pre>{@code
 * ReactorEngine engine = ReactorEngine.create("jdbc");
 * engine.addDataSource("mysql", "jdbc:mysql://localhost:3306/mydb", "root", "password");
 * // 直接 R2DBC 执行
 * }</pre>
 *
 * <p><b>多数据源模式：</b>自动路由到 {@link DataSourceConversion} SPI（如 Calcite 联邦查询）。</p>
 * <pre>{@code
 * ReactorEngine engine = ReactorEngine.create("jdbc");
 * engine.addDataSource("ds1", "jdbc:mysql://host1:3306/db1", "root", "pwd1");
 * engine.addDataSource("ds2", "jdbc:postgresql://host2:5432/db2", "user", "pwd2");
 * // 自动使用 Calcite 联邦查询
 * }</pre>
 *
 * <p>支持的数据库类型（根据 JDBC URL 自动识别并创建对应 R2DBC 连接工厂）：</p>
 * <ul>
 *   <li>MySQL — {@code jdbc:mysql://...}</li>
 *   <li>PostgreSQL — {@code jdbc:postgresql://...}</li>
 *   <li>MariaDB — {@code jdbc:mariadb://...}</li>
 *   <li>H2 — {@code jdbc:h2:...}</li>
 *   <li>SQL Server — {@code jdbc:sqlserver://...}</li>
 *   <li>Oracle — {@code jdbc:oracle:...}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("jdbc")
public class JdbcReactorEngine implements ReactorEngine {

    /** 默认数据源名称 */
    private String defaultDataSourceName;

    /** 数据源名称 → R2DBC 连接工厂（单数据源模式使用） */
    private final Map<String, ConnectionFactory> r2dbcFactories = new ConcurrentHashMap<>();

    /** 数据源名称 → JDBC DataSource（多数据源模式使用） */
    private final Map<String, DataSource> jdbcDataSources = new ConcurrentHashMap<>();

    /** 数据源名称 → 方言 */
    private final Map<String, Dialect> dialects = new ConcurrentHashMap<>();

    /** 统一的联邦数据源（多数据源模式） */
    private DataSource unifiedDataSource;

    /**
     * 添加 JDBC 数据源。根据数据源数量自动切换执行模式。
     *
     * @param name      数据源名称
     * @param jdbcUrl   JDBC URL（如 jdbc:mysql://localhost:3306/mydb）
     * @param username  用户名
     * @param password  密码
     * @return this
     */
    public JdbcReactorEngine addDataSource(String name, String jdbcUrl, String username, String password) {
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("JDBC URL cannot be null");
        }

        // 检测是否为 R2DBC URL
        if (jdbcUrl.startsWith("r2dbc:")) {
            r2dbcFactories.put(name, ConnectionFactories.get(jdbcUrl + buildParams(username, password)));
        } else {
            // JDBC URL → 同时创建 R2DBC 工厂和 JDBC DataSource
            String r2dbcUrl = jdbcUrl.replaceFirst("^jdbc:", "r2dbc:");
            r2dbcFactories.put(name, ConnectionFactories.get(r2dbcUrl + buildParams(username, password)));
            jdbcDataSources.put(name, createJdbcDataSource(jdbcUrl, username, password));
        }

        dialects.put(name, detectDialect(jdbcUrl));

        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }

        // 多数据源时重建联邦
        if (r2dbcFactories.size() > 1 || jdbcDataSources.size() > 1) {
            buildUnifiedDataSource();
        }

        return this;
    }

    /**
     * 添加 R2DBC 连接工厂。
     *
     * @param name     数据源名称
     * @param factory  R2DBC 连接工厂
     * @param dialect  方言
     * @return this
     */
    public JdbcReactorEngine addDataSource(String name, ConnectionFactory factory, Dialect dialect) {
        r2dbcFactories.put(name, factory);
        dialects.put(name, dialect);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        if (r2dbcFactories.size() > 1) {
            buildUnifiedDataSource();
        }
        return this;
    }

    /**
     * 构建统一数据源（多数据源联邦）。
     */
    private void buildUnifiedDataSource() {
        if (jdbcDataSources.isEmpty()) {
            // 只有 R2DBC 工厂，无法直接联邦，退回单数据源模式
            return;
        }
        List<DataSource> sources = new ArrayList<>(jdbcDataSources.values());
        DataSourceConversion conversion = ServiceProvider.of(DataSourceConversion.class).getDefault();
        if (conversion != null) {
            unifiedDataSource = conversion.convert(sources, new DataSourceEnvironment("jdbc-reactor", null, null, null));
        } else {
            // 无 Conversion SPI，退回第一个数据源
            unifiedDataSource = sources.get(0);
        }
    }

    /**
     * 根据 JDBC URL 检测方言。
     */
    private Dialect detectDialect(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
            return new com.chua.datasource.support.dialect.MysqlDialect();
        } else if (lower.startsWith("jdbc:postgresql:")) {
            return new com.chua.datasource.support.dialect.PostgresqlDialect();
        } else if (lower.startsWith("jdbc:h2:")) {
            return new com.chua.datasource.support.dialect.H2Dialect();
        } else if (lower.startsWith("jdbc:sqlserver:") || lower.startsWith("jdbc:mssql:")) {
            return new com.chua.datasource.support.dialect.SqlServerDialect();
        } else if (lower.startsWith("jdbc:oracle:")) {
            return new com.chua.datasource.support.dialect.Oracle12cDialect();
        }
        return null;
    }

    /**
     * 创建 JDBC DataSource（用于多数据源联邦）。
     */
    private static DataSource createJdbcDataSource(String jdbcUrl, String username, String password) {
        // 使用 DriverManager 创建简单 DataSource
        final String finalUrl = jdbcUrl;
        final String finalUser = username;
        final String finalPass = password;
        return new DataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                String url = finalUrl;
                if (finalUser != null && !finalUser.isEmpty()) {
                    url += (finalUrl.contains("?") ? "&" : "?") + "user=" + finalUser;
                }
                if (finalPass != null && !finalPass.isEmpty()) {
                    url += "&password=" + finalPass;
                }
                return java.sql.DriverManager.getConnection(url);
            }
            @Override
            public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
                return getConnection();
            }
            @Override
            public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { return null; }
            @Override
            public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException { return false; }
            @Override
            public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return null; }
            @Override
            public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {}
            @Override
            public void setLoginTimeout(int seconds) throws java.sql.SQLException {}
            @Override
            public int getLoginTimeout() throws java.sql.SQLException { return 0; }
            @Override
            public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return null; }
        };
    }

    private static String buildParams(String username, String password) {
        StringBuilder sb = new StringBuilder();
        if (username != null && !username.isEmpty()) {
            sb.append("?user=").append(username);
        }
        if (password != null && !password.isEmpty()) {
            sb.append(sb.length() > 0 ? "&" : "?").append("password=").append(password);
        }
        return sb.toString();
    }

    /**
     * 获取默认数据源名称。
     */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    /**
     * 获取指定数据源的 R2DBC 连接工厂。
     */
    public ConnectionFactory getR2dbcFactory(String name) {
        return r2dbcFactories.get(name);
    }

    /**
     * 获取指定数据源的方言。
     */
    public Dialect getDialect(String name) {
        return dialects.get(name);
    }

    /**
     * 是否多数据源模式。
     */
    public boolean isMultiDataSource() {
        return r2dbcFactories.size() > 1 || jdbcDataSources.size() > 1;
    }

    // ==================== ReactorEngine 接口实现 ====================

    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            // 多数据源模式：通过统一 DataSource 执行
            return new ReactorLambdaQueryWrapper<>(new UnifiedEngineAdapter(unifiedDataSource), entityClass);
        }
        // 单数据源模式：通过 R2DBC 执行
        return new ReactorLambdaQueryWrapper<>(new R2dbcEngineAdapter(), entityClass);
    }

    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return new ReactorLambdaUpdateWrapper<>(new UnifiedEngineAdapter(unifiedDataSource), entityClass);
        }
        return new ReactorLambdaUpdateWrapper<>(new R2dbcEngineAdapter(), entityClass);
    }

    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return new ReactorLambdaDeleteWrapper<>(new UnifiedEngineAdapter(unifiedDataSource), entityClass);
        }
        return new ReactorLambdaDeleteWrapper<>(new R2dbcEngineAdapter(), entityClass);
    }

    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return queryViaJdbc(unifiedDataSource, sql, params);
        }
        return queryViaR2dbc(defaultDataSourceName, sql, params);
    }

    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return queryTypedViaJdbc(unifiedDataSource, sql, rowType, params);
        }
        return queryTypedViaR2dbc(defaultDataSourceName, sql, rowType, params);
    }

    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return executeViaJdbc(unifiedDataSource, sql, params);
        }
        return executeViaR2dbc(defaultDataSourceName, sql, params);
    }

    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return batchViaJdbc(unifiedDataSource, sql, batchParams);
        }
        return batchViaR2dbc(defaultDataSourceName, sql, batchParams);
    }

    // ==================== R2DBC 执行路径（单数据源） ====================

    private Flux<Map<String, Object>> queryViaR2dbc(String name, String sql, Object... params) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params))
                        .flatMap(result -> Flux.from(result.map(this::toMap))),
                conn -> Mono.empty());
    }

    private <T> Flux<T> queryTypedViaR2dbc(String name, String sql, Class<T> rowType, Object... params) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params))
                        .flatMap(result -> Flux.from(result.map((row, meta) -> toObject(row, rowType)))),
                conn -> Mono.empty());
    }

    private Mono<Integer> executeViaR2dbc(String name, String sql, Object... params) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        return Mono.usingWhen(
                Mono.from(factory.create()),
                conn -> {
                    Statement stmt = conn.createStatement(sql);
                    bindParams(stmt, params);
                    return Flux.from(stmt.execute())
                            .flatMap(Result::getRowsUpdated)
                            .reduce(0L, Long::sum);
                },
                conn -> Mono.empty())
                .map(Long::intValue)
                .switchIfEmpty(Mono.just(0));
    }

    private Flux<Integer> batchViaR2dbc(String name, String sql, List<Object[]> batchParams) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        if (batchParams == null || batchParams.isEmpty()) {
            return Flux.empty();
        }
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.fromIterable(batchParams)
                        .flatMap(paramArray -> {
                            Statement stmt = conn.createStatement(sql);
                            bindParams(stmt, paramArray);
                            return Flux.from(stmt.execute())
                                    .flatMap(Result::getRowsUpdated)
                                    .reduce(0L, Long::sum);
                        })
                        .map(Long::intValue),
                conn -> Mono.empty())
                .next()
                .flatMapMany(Flux::just);
    }

    // ==================== JDBC 执行路径（多数据源联邦） ====================

    private Flux<Map<String, Object>> queryViaJdbc(DataSource ds, String sql, Object... params) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> result = new ArrayList<>();
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        result.add(row);
                    }
                }
            }
            return result;
        }).flatMapMany(Flux::fromIterable);
    }

    private <T> Flux<T> queryTypedViaJdbc(DataSource ds, String sql, Class<T> rowType, Object... params) {
        return Mono.fromCallable(() -> {
            List<T> result = new ArrayList<>();
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        T instance = rowType.getDeclaredConstructor().newInstance();
                        for (int i = 1; i <= colCount; i++) {
                            String label = meta.getColumnLabel(i);
                            Object value = rs.getObject(i);
                            if (value != null) {
                                setFieldValue(instance, label, value);
                            }
                        }
                        result.add(instance);
                    }
                }
            }
            return result;
        }).flatMapMany(Flux::fromIterable);
    }

    private Mono<Integer> executeViaJdbc(DataSource ds, String sql, Object... params) {
        return Mono.fromCallable(() -> {
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    private Flux<Integer> batchViaJdbc(DataSource ds, String sql, List<Object[]> batchParams) {
        if (batchParams == null || batchParams.isEmpty()) {
            return Flux.empty();
        }
        return Mono.fromCallable(() -> {
            List<Integer> results = new ArrayList<>();
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Object[] params : batchParams) {
                    for (int i = 0; i < params.length; i++) {
                        ps.setObject(i + 1, params[i]);
                    }
                    ps.addBatch();
                }
                int[] updates = ps.executeBatch();
                for (int update : updates) {
                    results.add(update);
                }
            }
            return results;
        }).flatMapMany(Flux::fromIterable);
    }

    // ==================== 静态辅助方法 ====================

    private static void bindParams(Statement stmt, Object... params) {
        if (params == null) return;
        for (int i = 0; i < params.length; i++) {
            stmt.bind(i, params[i]);
        }
    }

    @SuppressWarnings("unchecked")
    private static Publisher<Result> executeStatement(Connection conn, String sql, Object[] params) {
        Statement stmt = conn.createStatement(sql);
        bindParams(stmt, params);
        return (Publisher<Result>) (Publisher<?>) stmt.execute();
    }

    private Map<String, Object> toMap(Row row, RowMetadata meta) {
        Map<String, Object> rowMap = new LinkedHashMap<>();
        for (ColumnMetadata cm : meta.getColumnMetadatas()) {
            String name = cm.getName();
            if (name != null && !name.isEmpty()) {
                rowMap.put(name, row.get(name));
            }
        }
        return rowMap;
    }

    @SuppressWarnings("unchecked")
    private <T> T toObject(Row row, Class<T> rowType) {
        try {
            T instance = rowType.getDeclaredConstructor().newInstance();
            row.getMetadata().getColumnMetadatas().forEach(cm -> {
                String name = cm.getName();
                if (name != null && !name.isEmpty()) {
                    Object value = row.get(name);
                    if (value != null) setFieldValue(instance, name, value);
                }
            });
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException("映射行到 " + rowType.getName() + " 失败: " + e.getMessage(), e);
        }
    }

    private static void setFieldValue(Object instance, String columnName, Object value) {
        List<String> candidates = List.of(columnName, toCamelCase(columnName));
        for (String candidate : candidates) {
            java.lang.reflect.Field field = findField(instance.getClass(), candidate);
            if (field == null) continue;
            try {
                field.setAccessible(true);
                Object converted = com.chua.common.support.converter.Converter.convertIfNecessary(value, field.getType());
                if (converted != null) field.set(instance, converted);
                return;
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private static String toCamelCase(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') { upperNext = true; }
            else if (upperNext) { sb.append(Character.toUpperCase(c)); upperNext = false; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    // ==================== 内部适配器 ====================

    /**
     * 获取指定数据源的 EngineDataSource。
     */
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource(String name) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) return null;
        return new EngineDataSource<T>() {
            @Override public String name() { return name; }
            @Override public T getSource() { return null; }
            @Override public Dialect getDialect() { return dialects.get(name); }
            @Override public String url() { return null; }
            @Override public String username() { return null; }
            @Override public String password() { return null; }
            @Override public EngineDataSource<T> setSource(Object source) { return this; }
            @Override public EngineDataSource<T> setDialect(Dialect dialect) { return this; }
            @Override public int tunnelPort() { return 0; }
            @Override public EngineDataSource<T> setTunnelPort(int tunnelPort) { return this; }
            @Override public void close() {}
        };
    }

    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource() {
        return getDataSource(defaultDataSourceName);
    }

    // ==================== 内部适配器 ====================

    /** R2DBC 引擎适配器，供 Lambda 包装器使用 */
    private class R2dbcEngineAdapter implements Engine {
        @Override
        public <T> Engine addDataSource(String name, EngineDataSource<T> ds) { throw new UnsupportedOperationException(); }
        @Override
        public <T> Engine store(String name, List<T> data) { throw new UnsupportedOperationException(); }
        @Override
        public Engine setDefaultDataSourceName(String name) { JdbcReactorEngine.this.defaultDataSourceName = name; return this; }
        @Override
        public SqlExecutor getExecutor(String dataSourceName) {
            return new R2dbcSqlExecutorWrapper(r2dbcFactories.get(dataSourceName), dialects.get(dataSourceName));
        }
        @Override
        public SqlExecutor getExecutor() { return getExecutor(defaultDataSourceName); }
        @Override
        public <T> EngineDataSource<T> getDataSource(String name) { return JdbcReactorEngine.this.getDataSource(name); }
        @Override
        public <T> EngineDataSource<T> getDataSource() { return JdbcReactorEngine.this.getDataSource(); }
        @Override
        public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) { throw new UnsupportedOperationException(); }
        @Override
        public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) { throw new UnsupportedOperationException(); }
        @Override
        public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) { throw new UnsupportedOperationException(); }
        @Override
        public Dialect getDialect(String dataSourceName) { return dialects.get(dataSourceName); }
        @Override
        public String getDefaultDataSourceName() { return defaultDataSourceName; }
        @Override
        public void close() {}
    }

    /** 统一数据源适配器，供多数据源模式的 Lambda 包装器使用 */
    private class UnifiedEngineAdapter implements Engine {
        private final DataSource ds;
        UnifiedEngineAdapter(DataSource ds) { this.ds = ds; }
        @Override
        public <T> Engine addDataSource(String name, EngineDataSource<T> ds) { throw new UnsupportedOperationException(); }
        @Override
        public <T> Engine store(String name, List<T> data) { throw new UnsupportedOperationException(); }
        @Override
        public Engine setDefaultDataSourceName(String name) { return this; }
        @Override
        public SqlExecutor getExecutor(String dataSourceName) { return new JdbcSqlExecutorWrapper(ds); }
        @Override
        public SqlExecutor getExecutor() { return new JdbcSqlExecutorWrapper(ds); }
        @Override
        public <T> EngineDataSource<T> getDataSource(String name) { return null; }
        @Override
        public <T> EngineDataSource<T> getDataSource() { return null; }
        @Override
        public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) { throw new UnsupportedOperationException(); }
        @Override
        public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) { throw new UnsupportedOperationException(); }
        @Override
        public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) { throw new UnsupportedOperationException(); }
        @Override
        public Dialect getDialect(String dataSourceName) { return null; }
        @Override
        public String getDefaultDataSourceName() { return null; }
        @Override
        public void close() {}
    }

    /** R2DBC SqlExecutor 包装 */
    private class R2dbcSqlExecutorWrapper implements SqlExecutor {
        private final ConnectionFactory factory;
        private final Dialect dialect;
        R2dbcSqlExecutorWrapper(ConnectionFactory factory, Dialect dialect) {
            this.factory = factory;
            this.dialect = dialect;
        }
        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (factory == null) throw new IllegalStateException("R2DBC 连接工厂未配置");
            return Mono.from(Flux.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.from(executeStatement(conn, sql, params))
                            .flatMap(r -> Flux.from(r.map(JdbcReactorEngine.this::toMap)))
                            .collectList(),
                    conn -> Mono.empty())).block();
        }
        @Override
        public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
            if (factory == null) throw new IllegalStateException("R2DBC 连接工厂未配置");
            return Mono.from(Flux.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.from(executeStatement(conn, sql, params))
                            .flatMap(r -> Flux.from(r.map((row, meta) -> JdbcReactorEngine.this.toObject(row, rowType))))
                            .collectList(),
                    conn -> Mono.empty())).block();
        }
        @Override
        public List<Map<String, Object>> queryPage(String sql, com.chua.common.support.lang.datasource.dialect.Pagination pagination, Object... params) {
            long total = 0;
            try {
                String countSql = "SELECT COUNT(*) FROM (" + trimSql(sql) + ") t";
                List<Map<String, Object>> rows = query(countSql, params);
                if (!rows.isEmpty()) {
                    Object val = rows.get(0).values().iterator().next();
                    if (val instanceof Number n) total = n.longValue();
                }
            } catch (Exception ignored) {}
            pagination.setTotal(total);
            String pageSql = dialect != null ? dialect.processSql(sql, pagination) : sql;
            return query(pageSql, params);
        }
        @Override
        public int execute(String sql, Object... params) {
            if (factory == null) throw new IllegalStateException("R2DBC 连接工厂未配置");
            return Mono.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.from(executeStatement(conn, sql, params))
                            .flatMap(Result::getRowsUpdated).reduce(0L, Long::sum),
                    conn -> Mono.empty())
                    .map((Long l) -> l.intValue()).switchIfEmpty(Mono.just(0)).block();
        }
        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            if (factory == null) throw new IllegalStateException("R2DBC 连接工厂未配置");
            List<Integer> results = Mono.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.fromIterable(batchParams)
                            .flatMap(p -> { Statement s = conn.createStatement(sql); bindParams(s, p); return Flux.from(s.execute()).flatMap(Result::getRowsUpdated).reduce(0L, Long::sum); })
                            .map((Long l) -> l.intValue()).collectList(),
                    conn -> Mono.empty()).block();
            return results == null ? new int[0] : results.stream().mapToInt(Integer::intValue).toArray();
        }
        private static String trimSql(String sql) {
            if (sql == null) return "";
            String t = sql.trim();
            while (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
            return t;
        }
    }

    /** JDBC SqlExecutor 包装 */
    private class JdbcSqlExecutorWrapper implements SqlExecutor {
        private final DataSource ds;
        JdbcSqlExecutorWrapper(DataSource ds) { this.ds = ds; }
        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                List<Map<String, Object>> result = new ArrayList<>();
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                        result.add(row);
                    }
                }
                return result;
            } catch (Exception e) { throw new IllegalStateException("查询失败: " + sql, e); }
        }
        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                List<T> result = new ArrayList<>();
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        T instance = rowType.getDeclaredConstructor().newInstance();
                        for (int i = 1; i <= colCount; i++) {
                            String label = meta.getColumnLabel(i);
                            Object value = rs.getObject(i);
                            if (value != null) setFieldValue(instance, label, value);
                        }
                        result.add(instance);
                    }
                }
                return result;
            } catch (Exception e) { throw new IllegalStateException("查询失败: " + sql, e); }
        }
        @Override
        public List<Map<String, Object>> queryPage(String sql, com.chua.common.support.lang.datasource.dialect.Pagination pagination, Object... params) {
            long total = 0;
            try {
                String countSql = "SELECT COUNT(*) FROM (" + trimSql(sql) + ") t";
                List<Map<String, Object>> rows = query(countSql, params);
                if (!rows.isEmpty()) {
                    Object val = rows.get(0).values().iterator().next();
                    if (val instanceof Number n) total = n.longValue();
                }
            } catch (Exception ignored) {}
            pagination.setTotal(total);
            return query(sql, params);
        }
        @Override
        public int execute(String sql, Object... params) {
        try (java.sql.Connection conn = ds.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                return ps.executeUpdate();
            } catch (Exception e) { throw new IllegalStateException("执行失败: " + sql, e); }
        }
        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                if (batchParams != null) {
                    for (Object[] p : batchParams) {
                        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
                        ps.addBatch();
                    }
                }
                return ps.executeBatch();
            } catch (Exception e) { throw new IllegalStateException("批量执行失败: " + sql, e); }
        }
        private static String trimSql(String sql) {
            if (sql == null) return "";
            String t = sql.trim();
            while (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
            return t;
        }
    }
}
