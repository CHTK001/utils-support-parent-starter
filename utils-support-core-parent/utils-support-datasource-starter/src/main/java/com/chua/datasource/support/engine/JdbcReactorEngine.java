package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.network.net.NetAddress;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import io.r2dbc.spi.*;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;

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

    private static final Logger logger = LoggerFactory.getLogger(JdbcReactorEngine.class);

    /** R2DBC URL 协议前缀 */
    private static final String R2DBC_PREFIX = "r2dbc:";
    /** JDBC URL 协议前缀 */
    private static final String JDBC_PREFIX = "jdbc:";
    /** H2 驱动名称 */
    private static final String H2_DRIVER = "h2";
    /** H2 内存模式 */
    private static final String H2_MEM = "mem";
    /** H2 文件模式 */
    private static final String H2_FILE = "file";
    /** DDL 关键字：CREATE */
    private static final String DDL_CREATE = "CREATE ";
    /** DDL 关键字：DROP */
    private static final String DDL_DROP = "DROP ";
    /** DDL 关键字：ALTER */
    private static final String DDL_ALTER = "ALTER ";
    /** DDL 关键字：TRUNCATE */
    private static final String DDL_TRUNCATE = "TRUNCATE ";
    /** 匹配 jdbc: 前缀的正则（预编译） */
    private static final Pattern JDBC_PREFIX_PATTERN = Pattern.compile("^jdbc:");
    /** r2dbc-mssql 的 SimpleMssqlStatement 不支持参数绑定，SQL Server 统一走 JDBC 路径 */
    private static final String JDBC_PREFIX_SQLSERVER = "jdbc:sqlserver:";

    /** 默认数据源名称，首次 addDataSource 时自动设置 */
    private String defaultDataSourceName;
    /** 数据源名称 → R2DBC 连接工厂（单数据源模式使用） */
    private final Map<String, ConnectionFactory> r2dbcFactories = new ConcurrentHashMap<>();
    /** 数据源名称 → JDBC DataSource（多数据源联邦时使用） */
    private final Map<String, DataSource> jdbcDataSources = new ConcurrentHashMap<>();
    /** 数据源名称 → JDBC URL（用于占位符转换等） */
    private final Map<String, String> jdbcUrls = new ConcurrentHashMap<>();
    /** 数据源名称 → 方言 */
    private final Map<String, Dialect> dialects = new ConcurrentHashMap<>();
    /** 统一的联邦数据源（多数据源模式），无 SPI Conversion 时回退到首个 DataSource */
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
        /* 检测是否为 R2DBC URL */
        if (jdbcUrl.startsWith(R2DBC_PREFIX)) {
            r2dbcFactories.put(name, buildConnectionFactory(jdbcUrl, username, password));
        } else {
            /* JDBC URL → R2DBC URL 转换 */
            String r2dbcUrl = convertJdbcToR2dbc(jdbcUrl);
            r2dbcFactories.put(name, buildConnectionFactory(r2dbcUrl, username, password));
            jdbcDataSources.put(name, createJdbcDataSource(jdbcUrl, username, password));
            jdbcUrls.put(name, jdbcUrl);
        }

        dialects.put(name, detectDialect(jdbcUrl));

        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }

        /* 多数据源时重建联邦 */
        if (r2dbcFactories.size() > 1 || jdbcDataSources.size() > 1) {
            buildUnifiedDataSource();
        }

        return this;
    }

    /**
     * 添加 R2DBC 数据源（直接传入 R2DBC URL）。
     *
     * @param name     数据源名称
     * @param r2dbcUrl R2DBC URL（如 r2dbc:h2:mem://testdb）
     * @return this
     */
    public JdbcReactorEngine addDataSource(String name, String r2dbcUrl) {
        /* 兼容传入 JDBC URL 的情况，自动转换为 R2DBC URL */
        String url = r2dbcUrl;
        if (url != null && !url.startsWith(R2DBC_PREFIX)) {
            url = convertJdbcToR2dbc(url);
        }
        r2dbcFactories.put(name, buildConnectionFactory(url, null, null));
        dialects.put(name, detectR2dbcDialect(url));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        if (r2dbcFactories.size() > 1) {
            buildUnifiedDataSource();
        }
        return this;
    }

    /**
     * 添加 R2DBC 连接工厂。
     *
     * @param name    数据源名称
     * @param factory R2DBC 连接工厂
     * @param dialect 方言
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
     * 构建统一数据源（多数据源联邦），当 SPI DataSourceConversion 不可用时退回首个数据源。
     */
    private void buildUnifiedDataSource() {
        if (jdbcDataSources.isEmpty()) {
            /* 只有 R2DBC 工厂，无法直接联邦，退回单数据源模式 */
            return;
        }
        List<DataSource> sources = new ArrayList<>(jdbcDataSources.size());
        sources.addAll(jdbcDataSources.values());
        DataSourceConversion conversion = ServiceProvider.of(DataSourceConversion.class).getDefault();
        if (conversion != null) {
            unifiedDataSource = conversion.convert(sources, new DataSourceEnvironment("jdbc-reactor", null, null, null));
        } else {
            /* 无 Conversion SPI，退回第一个数据源 */
            unifiedDataSource = sources.get(0);
        }
    }

    /**
     * 构建 R2DBC ConnectionFactory，正确处理用户名密码。
     */
    @SuppressWarnings("unchecked")
    private static ConnectionFactory buildConnectionFactory(String url, String username, String password) {
        ConnectionFactoryOptions parsed = ConnectionFactoryOptions.parse(url);
        ConnectionFactoryOptions.Builder builder = ConnectionFactoryOptions.builder();

        /* 复制所有已解析的选项 */
        Object val;
        if ((val = parsed.getValue(DRIVER)) != null) builder.option(DRIVER, (String) val);
        if ((val = parsed.getValue(HOST)) != null) builder.option(HOST, (String) val);
        if ((val = parsed.getValue(PORT)) != null) builder.option(PORT, (Integer) val);
        if ((val = parsed.getValue(DATABASE)) != null) builder.option(DATABASE, (String) val);
        if ((val = parsed.getValue(PROTOCOL)) != null) builder.option(PROTOCOL, (String) val);
        if ((val = parsed.getValue(SSL)) != null) builder.option(SSL, (Boolean) val);

        /* H2 mem/file 模式，URL 解析会把 database 当成 host
         * 例如 r2dbc:h2:mem://testdb → host=testdb, database=null
         * 需要修正为 database=testdb, host=null
         */
        String driver = (String) parsed.getValue(DRIVER);
        String protocol = (String) parsed.getValue(PROTOCOL);
        if ("h2".equals(driver) && (H2_MEM.equals(protocol) || H2_FILE.equals(protocol))) {
            String host = (String) parsed.getValue(HOST);
            String db = (String) parsed.getValue(DATABASE);
            if (host != null && db == null) {
                /* 这是 H2 mem/file 模式，host 实际是 database 名 */
                builder.option(DATABASE, host);
                /* 不设置 HOST（移除默认的 null 值） */
            }
        }

        if (username != null && !username.isEmpty()) {
            builder.option(USER, username);
        }
        if (password != null && !password.isEmpty()) {
            builder.option(PASSWORD, password);
        }
        return ConnectionFactories.get(builder.build());
    }

    /**
     * 将 JDBC URL 转换为 R2DBC URL。
     * <ul>
     *   <li>jdbc:h2:mem:testdb → r2dbc:h2:mem://testdb</li>
     *   <li>jdbc:h2:file:./testdb → r2dbc:h2:file:///./testdb</li>
     *   <li>jdbc:mysql://host:3306/db → r2dbc:mysql://host:3306/db</li>
     * </ul>
     */
    private static String convertJdbcToR2dbc(String jdbcUrl) {
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("JDBC URL cannot be null");
        }
        String r2dbcUrl = JDBC_PREFIX_PATTERN.matcher(jdbcUrl).replaceFirst(R2DBC_PREFIX);
        /* H2 内存/文件模式: jdbc:h2:mem:testdb → r2dbc:h2:mem://testdb */
        int h2Idx = r2dbcUrl.indexOf(R2DBC_PREFIX + H2_DRIVER + ":");
        if (h2Idx >= 0) {
            String suffix = r2dbcUrl.substring(h2Idx + (R2DBC_PREFIX + H2_DRIVER + ":").length());
            int colonIdx = suffix.indexOf(':');
            if (colonIdx >= 0) {
                String protocol = suffix.substring(0, colonIdx); /* "mem" or "file" */
                String database = suffix.substring(colonIdx + 1);
                return "r2dbc:h2:" + protocol + "://" + database;
            }
        }
        /* SQL Server: jdbc:sqlserver://host:port;databaseName=db;key=val → r2dbc:mssql://host:port/db?key=val */
        if (r2dbcUrl.startsWith(R2DBC_PREFIX + "sqlserver://") || r2dbcUrl.startsWith(R2DBC_PREFIX + "mssql://")) {
            /* 提取 host:port 部分（到第一个 ; 为止） */
            int semiIdx = r2dbcUrl.indexOf(';', r2dbcUrl.indexOf("://") + 3);
            String hostPort = semiIdx >= 0 ? r2dbcUrl.substring(0, semiIdx) : r2dbcUrl;
            String properties = semiIdx >= 0 ? r2dbcUrl.substring(semiIdx + 1) : "";
            /* 解析 databaseName */
            String database = "master";
            String paramPart = "";
            if (!properties.isEmpty()) {
                /* 分割多个 ; 分隔的 key=value */
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (String kv : properties.split(";")) {
                    String trimmed = kv.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int eqIdx = trimmed.indexOf('=');
                    if (eqIdx < 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, eqIdx).trim();
                    String value = trimmed.substring(eqIdx + 1).trim();
                    if ("databasename".equalsIgnoreCase(key)) {
                        database = value;
                    } else {
                        if (!first) {
                            sb.append("&");
                        }
                        sb.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
                        sb.append("=");
                        sb.append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                        first = false;
                    }
                }
                paramPart = sb.toString();
            }
            /* 从 hostPort 提取 host 和 port */
            String urlWithoutScheme = hostPort.substring(hostPort.indexOf("://") + 3);
            int portIdx = urlWithoutScheme.indexOf(':');
            String host = portIdx >= 0 ? urlWithoutScheme.substring(0, portIdx) : urlWithoutScheme;
            String port = portIdx >= 0 ? urlWithoutScheme.substring(portIdx + 1) : "1433";
            String result = "r2dbc:mssql://" + host + ":" + port + "/" + database;
            if (!paramPart.isEmpty()) {
                result += "?" + paramPart;
            }
            return result;
        }
        return r2dbcUrl;
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
     * 根据 R2DBC URL 检测方言。
     */
    private Dialect detectR2dbcDialect(String r2dbcUrl) {
        if (r2dbcUrl == null) {
            return null;
        }
        String lower = r2dbcUrl.toLowerCase();
        if (lower.startsWith("r2dbc:mysql:") || lower.startsWith("r2dbc:mariadb:")) {
            return new com.chua.datasource.support.dialect.MysqlDialect();
        } else if (lower.startsWith("r2dbc:postgresql:")) {
            return new com.chua.datasource.support.dialect.PostgresqlDialect();
        } else if (lower.startsWith("r2dbc:h2:")) {
            return new com.chua.datasource.support.dialect.H2Dialect();
        } else if (lower.startsWith("r2dbc:sqlserver:") || lower.startsWith("r2dbc:mssql:")) {
            return new com.chua.datasource.support.dialect.SqlServerDialect();
        } else if (lower.startsWith("r2dbc:oracle:")) {
            return new com.chua.datasource.support.dialect.Oracle12cDialect();
        }
        return null;
    }

    /**
     * 创建 JDBC DataSource（用于多数据源联邦）。
     * 直接通过 {@code DriverManager.getConnection(url, user, pass)} 传递凭据，
     * 避免将 user/password 追加到已含 query 参数的 JDBC URL 中导致格式错误。
     */
    private static DataSource createJdbcDataSource(String jdbcUrl, String username, String password) {
        final String finalUrl = jdbcUrl;
        final String finalUser = username;
        final String finalPass = password;
        return new DataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                return java.sql.DriverManager.getConnection(finalUrl, finalUser, finalPass);
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

    /* ==================== ReactorEngine 接口实现 ==================== */

    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            /* 多数据源模式：通过统一 DataSource 执行 */
            return new ReactorLambdaQueryWrapper<>(new UnifiedEngineAdapter(unifiedDataSource), entityClass);
        }
        /* 单数据源模式：通过 R2DBC 执行 */
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
        if (defaultDataSourceName == null) {
            return Flux.error(new IllegalStateException("未配置数据源，无法执行查询"));
        }
        return queryViaR2dbc(defaultDataSourceName, sql, params);
    }

    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return queryTypedViaJdbc(unifiedDataSource, sql, rowType, params);
        }
        if (defaultDataSourceName == null) {
            return Flux.error(new IllegalStateException("未配置数据源，无法执行查询"));
        }
        return queryTypedViaR2dbc(defaultDataSourceName, sql, rowType, params);
    }

    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return executeViaJdbc(unifiedDataSource, sql, params);
        }
        if (defaultDataSourceName == null) {
            return Mono.error(new IllegalStateException("未配置数据源，无法执行语句"));
        }
        return executeViaR2dbc(defaultDataSourceName, sql, params);
    }

    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        if (isMultiDataSource() && unifiedDataSource != null) {
            return batchViaJdbc(unifiedDataSource, sql, batchParams);
        }
        if (defaultDataSourceName == null) {
            throw new IllegalStateException("未配置数据源，无法执行批次");
        }
        return batchViaR2dbc(defaultDataSourceName, sql, batchParams);
    }

    /* ==================== R2DBC 执行路径（单数据源） ==================== */

    private Flux<Map<String, Object>> queryViaR2dbc(String name, String sql, Object... params) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        /* 使用 R2DBC 连接执行查询，返回 Map 列表 */
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params, jdbcUrls.get(name)))
                        .flatMap(result -> Flux.from(result.map(this::toMap))),
                conn -> Mono.empty());
    }

    private <T> Flux<T> queryTypedViaR2dbc(String name, String sql, Class<T> rowType, Object... params) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        /* 使用 R2DBC 连接执行查询，按 rowType 映射结果 */
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params, jdbcUrls.get(name)))
                        .flatMap(result -> Flux.from(result.map((row, meta) -> toObject(row, rowType)))),
                conn -> Mono.empty());
    }

    private Mono<Integer> executeViaR2dbc(String name, String sql, Object... params) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        /* DDL（CREATE/DROP/ALTER）或 SQL Server（r2dbc-mssql 有已知 bug）：若配置了 JDBC 数据源则走 JDBC 路径 */
        if (isDdl(sql) || jdbcUrls.get(name) != null && jdbcUrls.get(name).startsWith(JDBC_PREFIX_SQLSERVER)) {
            DataSource ds = jdbcDataSources.get(name);
            if (ds != null) {
                return executeViaJdbc(ds, sql, params);
            }
            /* 未配置 JDBC 数据源时退回 R2DBC 路径（H2 等驱动正常工作） */
        }
        /* MySQL 驱动（asyncer r2dbc-mysql 1.4.2）getRowsUpdated() 内部 MonoReduce
         * 对 Integer emission 做 Long 聚合时产生 ClassCastException（驱动层 bug）。
         * 通过 onErrorResume 降级到同步 JDBC 执行，保证生产可用性。 */
        return Mono.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params, jdbcUrls.get(name)))
                        .flatMap(result -> safeGetRowsUpdated(result))
                        .collectList()
                        .map(list -> list.stream().mapToLong(Long::longValue).sum()),
                conn -> Mono.empty())
                .map(l -> l.intValue())
                .defaultIfEmpty(0)
                .onErrorResume(ClassCastException.class, e -> {
                    DataSource ds = jdbcDataSources.get(name);
                    if (ds != null) {
                        return executeViaJdbc(ds, sql, params);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * 安全获取 rowsUpdated：H2 多语句批量执行时非 DML Result 会抛出异常，MySQL 驱动的
     * getRowsUpdated() 内部 MonoReduce 对 Integer/Long 不兼容，统一 catch 返回 empty。
     *
     * @param result R2DBC Result 对象
     * @return 受影响行数流
     */
    private static Flux<Long> safeGetRowsUpdated(io.r2dbc.spi.Result result) {
        try {
            return Flux.from(result.getRowsUpdated())
                    .map(v -> v instanceof Number n ? n.longValue() : 0L);
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    /**
     * 判断 SQL 是否为 DDL 语句（CREATE/DROP/ALTER/TRUNCATE）。
     *
     * @param sql SQL 语句
     * @return true 表示 DDL
     */
    private static boolean isDdl(String sql) {
        if (sql == null) {
            return false;
        }
        String trimmed = sql.trim().toUpperCase();
        if (trimmed.startsWith(DDL_CREATE) || trimmed.startsWith(DDL_DROP)
                || trimmed.startsWith(DDL_ALTER) || trimmed.startsWith(DDL_TRUNCATE)) {
            return true;
        }
        /* SQL Server 兼容：IF OBJECT_ID(...) IS NOT NULL DROP TABLE ... */
        return trimmed.contains("IF OBJECT_ID") && trimmed.contains("DROP TABLE");
    }

    /**
     * 通过 R2DBC 执行批量 SQL，每批次单独创建 Statement 并执行。
     * MySQL 驱动发生 ClassCastException 时降级到 JDBC 路径。
     *
     * @param name         数据源名称
     * @param sql          SQL 语句（含占位符）
     * @param batchParams  批量参数列表
     * @return 受影响行数流
     */
    private Flux<Integer> batchViaR2dbc(String name, String sql, List<Object[]> batchParams) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            throw new IllegalStateException("数据源 '" + name + "' 未配置");
        }
        if (batchParams == null || batchParams.isEmpty()) {
            return Flux.empty();
        }
        /* SQL Server 统一走 JDBC 路径（r2dbc-mssql 有已知 bug） */
        if (jdbcUrls.get(name) != null && jdbcUrls.get(name).startsWith(JDBC_PREFIX_SQLSERVER)) {
            DataSource ds = jdbcDataSources.get(name);
            if (ds != null) {
                return batchViaJdbc(ds, sql, batchParams);
            }
        }
         /* 每批次单独创建 Statement 并执行，通过 safeGetRowsUpdated 兼容各驱动差异 */
        return Flux.from(Mono.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.fromIterable(batchParams)
                        .flatMap(paramArray -> {
                             Statement stmt = conn.createStatement(convertPlaceholders(sql, null));
                             bindParams(stmt, paramArray);
                             return Flux.from(stmt.execute())
                                     .flatMap(result -> safeGetRowsUpdated(result))
                                     .collectList()
                                     .map(list -> list.isEmpty() ? 0L : list.stream().mapToLong(Long::longValue).sum());
                        })
                        .collectList()
                        .map(list -> list == null || list.isEmpty() ? 0 : list.stream().mapToInt(Long::intValue).sum()),
                conn -> Mono.empty()))
                .onErrorResume(ClassCastException.class, e -> {
                    /* MySQL 驱动批量操作 ClassCastException，降级到 JDBC 路径 */
                    DataSource ds = jdbcDataSources.get(name);
                    if (ds != null) {
                        return batchViaJdbc(ds, sql, batchParams);
                    }
                    return Flux.error(e);
                })
                .onErrorResume(UnsupportedOperationException.class, e -> {
                    /* SQL Server r2dbc-mssql 不支持参数绑定，降级到 JDBC */
                    logger.warn("batchViaR2dbc falling back to JDBC due to: {}", e.getMessage());
                    DataSource ds = jdbcDataSources.get(name);
                    if (ds != null) {
                        return batchViaJdbc(ds, sql, batchParams);
                    }
                    return Flux.error(e);
                });
    }

    /* ==================== JDBC 执行路径（多数据源联邦） ==================== */

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
                        T instance = ReflectUtils.instantiate(rowType);
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
                System.out.println("[DEBUG] batchViaJdbc: sql=" + sql + " batches=" + batchParams.size() + " updates=" + java.util.Arrays.toString(updates));
                for (int update : updates) {
                    results.add(update);
                }
            } catch (Exception e) {
                System.err.println("[DEBUG] batchViaJdbc FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            return results;
        }).flatMapMany(Flux::fromIterable);
    }

    /* ==================== 静态辅助方法 ==================== */

    /**
     * 将 SQL 参数绑定到 R2DBC Statement。
     *
     * @param stmt   R2DBC Statement
     * @param params 参数数组
     */
    private static void bindParams(Statement stmt, Object... params) {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            stmt.bind(i, params[i]);
        }
    }

    /**
     * 执行 R2DBC 语句并返回结果流。根据方言转换占位符（PostgreSQL 使用 $1, $2 而非 ?）。
     *
     * @param conn  R2DBC 连接
     * @param sql   SQL 语句
     * @param params 参数
     * @param jdbcUrl JDBC URL（用于判断方言）
     * @return Result 发布流
     */
    @SuppressWarnings("unchecked")
    private static Publisher<Result> executeStatement(Connection conn, String sql, Object[] params, String jdbcUrl) {
        sql = convertPlaceholders(sql, jdbcUrl);
        Statement stmt = conn.createStatement(sql);
        bindParams(stmt, params);
        return (Publisher<Result>) (Publisher<?>) stmt.execute();
    }

    /**
     * 将 SQL 中的 ? 占位符转换为当前方言对应的格式。
     * PostgreSQL r2dbc 驱动使用 $1, $2 风格；其他驱动保持 ?。
     */
    private static String convertPlaceholders(String sql, String jdbcUrl) {
        if (sql == null || !sql.contains("?")) {
            return sql;
        }
        if (jdbcUrl != null && jdbcUrl.toLowerCase().startsWith("jdbc:postgresql:")) {
            return convertQuestionMarksToDollars(sql);
        }
        return sql;
    }

    /**
     * 将 SQL 中的 ? 替换为 $1, $2, ...（PostgreSQL R2DBC 参数格式）。
     */
    private static String convertQuestionMarksToDollars(String sql) {
        StringBuilder sb = new StringBuilder(sql.length() + 16);
        int paramIndex = 1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                sb.append('$').append(paramIndex++);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将 R2DBC Row 转换为 Map（列名 → 值）。
     *
     * @param row  R2DBC 行数据
     * @param meta 列元数据
     * @return 列名映射的 Map
     */
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

    /**
     * 将 R2DBC Row 映射为目标 POJO 对象。
     *
     * @param row      R2DBC 行数据
     * @param rowType  目标类型
     * @param <T>      泛型类型
     * @return 映射后的实例
     */
    @SuppressWarnings("unchecked")
    private <T> T toObject(Row row, Class<T> rowType) {
        try {
            T instance = ReflectUtils.instantiate(rowType);
            row.getMetadata().getColumnMetadatas().forEach(cm -> {
                String name = cm.getName();
                if (name != null && !name.isEmpty()) {
                    Object value = row.get(name);
                    if (value != null) {
                        setFieldValue(instance, name, value);
                    }
                }
            });
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException("映射行到 " + rowType.getName() + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过反射将数据库列值设置到 POJO 字段，支持下划线转驼峰匹配。
     *
     * @param instance       POJO 实例
     * @param columnName     数据库列名
     * @param value          列值
     */
    private static void setFieldValue(Object instance, String columnName, Object value) {
        List<String> candidates = List.of(columnName, toCamelCase(columnName));
        for (String candidate : candidates) {
            java.lang.reflect.Field field = findField(instance.getClass(), candidate);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object converted = com.chua.common.support.converter.Converter.convertIfNecessary(value, field.getType());
                if (converted != null) {
                    field.set(instance, converted);
                }
                return;
            } catch (IllegalAccessException e) {
                logger.debug("反射设置字段失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 在类继承链中查找指定名称的字段。
     *
     * @param clazz  类
     * @param name   字段名
     * @return 找到的 Field，未找到返回 null
     */
    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                /* 父类继续查找，找不到则返回 null */
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 将下划线命名的字符串转换为驼峰命名（首字母小写）。
     *
     * @param name 原始字符串
     * @return 驼峰命名结果
     */
    private static String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /* ==================== 内部适配器 ==================== */

    /**
     * 获取指定数据源的 EngineDataSource。
     */
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource(String name) {
        ConnectionFactory factory = r2dbcFactories.get(name);
        if (factory == null) {
            return null;
        }
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

    /**
     * 关闭引擎，释放所有资源。
     */
    public void close() {
        for (ConnectionFactory f : r2dbcFactories.values()) {
            if (f instanceof AutoCloseable ac) {
                try {
                    ac.close();
                } catch (Exception e) {
                    logger.warn("关闭 R2DBC 连接工厂失败: {}", e.getMessage());
                }
            }
        }
        r2dbcFactories.clear();
        jdbcDataSources.clear();
        dialects.clear();
        unifiedDataSource = null;
        defaultDataSourceName = null;
    }

    /* ==================== 内部适配器（Lambda 包装器） ==================== */

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
            if (factory == null) {
                throw new IllegalStateException("R2DBC 连接工厂未配置");
            }
            return Mono.from(Flux.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.from(executeStatement(conn, sql, params, null))
                            .flatMap(r -> Flux.from(r.map(JdbcReactorEngine.this::toMap)).collectList()),
                    conn -> Mono.empty())).block();
        }
        @Override
        public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
            if (factory == null) {
                throw new IllegalStateException("R2DBC 连接工厂未配置");
            }
            return Mono.from(Flux.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.from(executeStatement(conn, sql, params, null))
                            .flatMap(r -> Flux.from(r.map((row, meta) -> JdbcReactorEngine.this.toObject(row, rowType)))
                                    .collectList()),
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
            } catch (Exception e) {
                logger.warn("R2DBC 分页查询总条数失败，将不使用总数: {}", e.getMessage());
            }
            pagination.setTotal(total);
            String pageSql = dialect != null ? dialect.processSql(sql, pagination) : sql;
            return query(pageSql, params);
        }
        @Override
        public int execute(String sql, Object... params) {
            if (factory == null) {
                throw new IllegalStateException("R2DBC 连接工厂未配置");
            }
            return Mono.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.from(executeStatement(conn, sql, params, null))
                            .flatMap(r -> safeGetRowsUpdated(r))
                            .collectList()
                            .map(list -> list.stream().mapToLong(Long::longValue).sum()),
                    conn -> Mono.empty())
                    .map((Long l) -> l.intValue()).switchIfEmpty(Mono.just(0)).block();
        }
        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            if (factory == null) {
                throw new IllegalStateException("R2DBC 连接工厂未配置");
            }
            Integer total = Mono.usingWhen(Mono.from(factory.create()),
                    conn -> Flux.fromIterable(batchParams)
                            .flatMap(p -> {
                                Statement s = conn.createStatement(sql);
                                bindParams(s, p);
                                return Mono.from(Flux.from(s.execute())
                                        .flatMap(result -> safeGetRowsUpdated(result))
                                        .collectList())
                                        .map(list -> list.isEmpty() ? 0L : list.stream().mapToLong(Long::longValue).sum());
                            })
                            .collectList()
                            .map(list -> list == null || list.isEmpty() ? 0 : list.stream().mapToInt(Long::intValue).sum()),
                    conn -> Mono.from(conn.close()))
                    .block();
            return total == null ? new int[0] : new int[]{total};
        }
        private static String trimSql(String sql) {
            if (sql == null) {
                return "";
            }
            String t = sql.trim();
            while (t.endsWith(";")) {
                t = t.substring(0, t.length() - 1).trim();
            }
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
                        T instance = ReflectUtils.instantiate(rowType);
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
            } catch (Exception e) {
                logger.warn("JDBC 分页查询总条数失败，将不使用总数: {}", e.getMessage());
            }
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
            if (sql == null) {
                return "";
            }
            String t = sql.trim();
            while (t.endsWith(";")) {
                t = t.substring(0, t.length() - 1).trim();
            }
            return t;
        }
    }
}
