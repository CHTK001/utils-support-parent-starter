package com.chua.common.support.concurrent.pool;

import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import com.chua.common.support.utils.StringUtils;

/**
 * JDBC 连接池
 *
 * <p>基于 {@link GenericObjectPool} 封装的数据库连接池，提供开箱即用的连接管理能力。
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>连接创建：通过 JDBC URL + 用户名密码自动创建</li>
 *   <li>连接初始化：支持自定义 initSQL、autoCommit、transactionIsolation 等</li>
 *   <li>连接验证：借出时自动 ping 检测连接有效性</li>
 *   <li>连接销毁：自动关闭失效连接</li>
 *   <li>空闲淘汰：定时清理超时空闲连接</li>
 *   <li>借出超时：等待连接可用时的超时控制</li>
 *   <li>自动归还：支持 try-with-resources（guard.get()）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   // 创建连接池
 *   ConnectionPool pool = ConnectionPool.builder()
 *       .url("jdbc:mysql://localhost:3306/mydb")
 *       .username("root")
 *       .password("123456")
 *       .maxTotal(20)
 *       .borrowTimeoutMillis(5000)
 *       .build();
 *
 *   // 借出连接
 *   Connection conn = pool.borrow();
 *   try {
 *       PreparedStatement ps = conn.prepareStatement("SELECT * FROM users");
 *       ResultSet rs = ps.executeQuery();
 *   } finally {
 *       pool.returnObject(conn);
 *   }
 *
 *   // try-with-resources
 *   try (var guard = pool.guard()) {
 *       Connection conn = guard.get();
 *       conn.prepareStatement("SELECT * FROM users").executeQuery();
 *   }
 *
 *   // 关闭连接池
 *   pool.close();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class ConnectionPool extends GenericObjectPool<Connection> {

    /**
     * JDBC URL
     */
    private final String url;

    /**
     * 用户名
     */
    private final String username;

    /**
     * 密码
     */
    private final String password;

    /**
     * JDBC 驱动属性
     */
    private final Properties driverProperties;

    /**
     * 验证 SQL（用于 testOnBorrow/testOnReturn 时检测连接有效性）
     */
    private final String validationQuery;

    /**
     * 初始化 SQL 列表（每个新连接创建后执行）
     */
    private final String[] initSqls;

    /**
     * 自动提交设置
     */
    private final Boolean defaultAutoCommit;

    /**
     * 事务隔离级别
     */
    private final Integer defaultTransactionIsolation;

    /**
     * 创建 ConnectionPool 实例
     * @param builder builder
     */
    private ConnectionPool(Builder builder) {
        super(builder.buildConfig(), new ManagedConnectionFactory(new ConnectionFactory(builder)));
        this.url = builder.url;
        this.username = builder.username;
        this.password = builder.password;
        this.driverProperties = builder.driverProperties;
        this.validationQuery = builder.validationQuery;
        this.initSqls = builder.initSqls;
        this.defaultAutoCommit = builder.defaultAutoCommit;
        this.defaultTransactionIsolation = builder.defaultTransactionIsolation;
    }

    /**
     * 使用自定义 JDBC 连接工厂创建连接池。
     *
     * <p>自定义工厂可负责驱动加载、SSH 隧道或其他建连方式；本类统一管理连接会话重置，
     * 并通过 {@link #borrowConnection()} 提供 close-to-return 租约语义。</p>
     *
     * @param config 池配置
     * @param factory JDBC 连接工厂
     */
    public ConnectionPool(ObjectPoolConfig config, ObjectFactory<Connection> factory) {
        super(config, new ManagedConnectionFactory(Objects.requireNonNull(factory, "factory")));
        this.url = null;
        this.username = null;
        this.password = null;
        this.driverProperties = new Properties();
        this.validationQuery = null;
        this.initSqls = null;
        this.defaultAutoCommit = null;
        this.defaultTransactionIsolation = null;
    }

    /**
     * 借出一条独占连接租约；关闭租约时归还池，连接类 SQLState 会使物理连接失效。
     *
     * @return 独占连接代理
     * @throws Exception 借出失败
     */
    public Connection borrowConnection() throws Exception {
        Connection connection = borrow();
        try {
            return new ConnectionLease(connection).proxy;
        } catch (RuntimeException | Error e) {
            invalidateObject(connection);
            throw e;
        }
    }

    /** 连接及其 JDBC 子句柄共用租约门禁，归还前关闭全部未关闭的 Statement。 */
    private final class ConnectionLease implements InvocationHandler {
        private final Connection connection;
        private final Connection proxy;
        private final Map<Object, Object> handles = new IdentityHashMap<>();
        private final Map<Object, Boolean> closedHandles = new IdentityHashMap<>();
        private boolean returned;
        private boolean invalid;

        private ConnectionLease(Connection connection) {
            this.connection = connection;
            this.proxy = (Connection) Proxy.newProxyInstance(ConnectionPool.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public synchronized Object invoke(Object handle, Method method, Object[] args) throws Throwable {
            return invokeHandle(connection, handle, method, args);
        }

        private synchronized Object invokeHandle(Object target, Object handle, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> handle == args[0];
                    case "hashCode" -> System.identityHashCode(handle);
                    case "toString" -> "PooledJdbcHandle[returned=" + returned + "]";
                    default -> ReflectUtils.invokeProxy(target, method, args);
                };
            }
            if ("close".equals(name)) {
                if (target == connection) {
                    release();
                } else if (!returned && !closedHandles.containsKey(target)) {
                    try {
                        invokePhysical(target, method, args);
                    } catch (Throwable failure) {
                        invalid = true;
                        throw failure;
                    } finally {
                        closedHandles.put(target, Boolean.TRUE);
                    }
                }
                return null;
            }
            if ("isClosed".equals(name) && (returned || closedHandles.containsKey(target))) {
                return true;
            }
            if (returned || closedHandles.containsKey(target)) {
                throw new SQLException("JDBC 租约或句柄已关闭");
            }
            if ("getConnection".equals(name) && method.getReturnType() == Connection.class) {
                return proxy;
            }
            if (args != null && args.length == 1 && args[0] instanceof Class<?> type) {
                if ("isWrapperFor".equals(name)) {
                    return type.isInstance(handle);
                }
                if ("unwrap".equals(name)) {
                    if (type.isInstance(handle)) {
                        return handle;
                    }
                    throw new SQLFeatureNotSupportedException("连接租约不暴露底层 JDBC 句柄: " + type.getName());
                }
            }
            if (target == connection && "abort".equals(name)) {
                invalid = true;
            }
            return wrap(invokePhysical(target, method, args));
        }

        private Object invokePhysical(Object target, Method method, Object[] args) throws Throwable {
            try {
                return ReflectUtils.invokeProxy(target, method, args);
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("08")) {
                    invalid = true;
                }
                throw e;
            }
        }

        private Object wrap(Object value) {
            if (value == connection) {
                return proxy;
            }
            Class<?> type = switch (value) {
                case CallableStatement ignored -> CallableStatement.class;
                case PreparedStatement ignored -> PreparedStatement.class;
                case Statement ignored -> Statement.class;
                case ResultSet ignored -> ResultSet.class;
                case DatabaseMetaData ignored -> DatabaseMetaData.class;
                case null, default -> null;
            };
            if (type == null) {
                return value;
            }
            return handles.computeIfAbsent(value, target -> Proxy.newProxyInstance(
                    ConnectionPool.class.getClassLoader(), new Class<?>[]{type},
                    (handle, method, args) -> invokeHandle(target, handle, method, args)));
        }

        private void release() {
            if (returned) {
                return;
            }
            returned = true;
            for (Object target : handles.keySet()) {
                if ((target instanceof Statement || target instanceof ResultSet)
                        && !closedHandles.containsKey(target)) {
                    try {
                        ((AutoCloseable) target).close();
                    } catch (Exception | LinkageError e) {
                        invalid = true;
                        log.debug("[pool] JDBC 子句柄清理失败，销毁物理连接: {}", e.getMessage());
                    }
                }
            }
            handles.clear();
            closedHandles.clear();
            if (invalid) {
                invalidateObject(connection);
            } else {
                returnObject(connection);
            }
        }
    }

    /**
     * 创建 Builder
     *
     * @return 新的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取Stats
     */
    @Override
    public String getStats() {
        return "ConnectionPool{url=" + url
                + ", maxTotal=" + getNumActive() + "+" + getNumIdle()
                + ", active=" + getNumActive()
                + ", idle=" + getNumIdle() + "}";
    }

    /**
     * 连接工厂（内部类）
     *
     * <p>实现 ObjectFactory&lt;Connection&gt;，负责连接的创建、初始化、验证和销毁。
     */
    private static class ConnectionFactory implements ObjectFactory<Connection> {

        /**
         * 构建器
         */
        private final Builder builder;

        /**
         * Connection Factory
         * @param builder 参数
         * @return 返回值
        */
        ConnectionFactory(Builder builder) {
            this.builder = builder;
        }

        /**
         * 创建
         */
        @Override
        public Connection create() throws Exception {
            Properties props = new Properties();
            if (builder.username != null) {
                props.setProperty("user", builder.username);
            }
            if (builder.password != null) {
                props.setProperty("password", builder.password);
            }
            if (builder.driverProperties != null) {
                props.putAll(builder.driverProperties);
            }
            return DriverManager.getConnection(builder.url, props);
        }

        /**
         * 初始化Object
         */
        @Override
        public void initObject(Connection conn) throws Exception {
            // 设置 autoCommit
            if (builder.defaultAutoCommit != null) {
                conn.setAutoCommit(builder.defaultAutoCommit);
            }
            // 设置事务隔离级别
            if (builder.defaultTransactionIsolation != null) {
                conn.setTransactionIsolation(builder.defaultTransactionIsolation);
            }
            // 执行初始化 SQL
            if (builder.initSqls != null) {
                for (String sql : builder.initSqls) {
                    if (sql != null && !sql.isBlank()) {
                        try (var statement = conn.createStatement()) {
                            statement.execute(sql);
                        }
                    }
                }
            }
        }

        /**
         * 销毁
         */
        @Override
        public void destroy(Connection conn) {
            if (conn == null) {
                return;
            }
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                log.debug("[pool] 销毁连接失败，已忽略: {}", e.getMessage());
            }
        }

        /**
         * 校验
         */
        @Override
        public boolean validate(Connection conn) {
            if (conn == null) {
                return false;
            }
            try {
                if (conn.isClosed()) {
                    return false;
                }
                // 使用验证 SQL 检测
                if (builder.validationQuery != null && !builder.validationQuery.isBlank()) {
                    try (var stmt = conn.createStatement()) {
                        stmt.execute(builder.validationQuery);
                    }
                    return true;
                }
                // 使用 JDBC 4.0 isValid 检测
                return conn.isValid(3);
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /** 在所有连接池实现上统一应用 JDBC 会话隔离与归还前重置。 */
    private static final class ManagedConnectionFactory implements ObjectFactory<Connection> {
        private final ObjectFactory<Connection> delegate;
        private final Map<Connection, ConnectionState> states =
                Collections.synchronizedMap(new IdentityHashMap<>());

        private ManagedConnectionFactory(ObjectFactory<Connection> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection create() throws Exception {
            return delegate.create();
        }

        @Override
        public void initObject(Connection connection) throws Exception {
            delegate.initObject(connection);
            states.put(connection, ConnectionState.capture(connection));
        }

        @Override
        public void passivateObject(Connection connection) throws Exception {
            ConnectionState state = states.get(connection);
            if (state == null) {
                throw new SQLException("JDBC 连接缺少会话基线");
            }
            state.restore(connection);
            delegate.passivateObject(connection);
        }

        @Override
        public boolean validate(Connection connection) {
            return delegate.validate(connection);
        }

        @Override
        public void destroy(Connection connection) {
            states.remove(connection);
            delegate.destroy(connection);
        }
    }

    /** 捕获物理连接的初始会话设置，并在每次租约归还时恢复。 */
    private record ConnectionState(boolean autoCommit, JdbcSetting<Boolean> readOnly, JdbcSetting<Integer> isolation,
                                   JdbcSetting<String> catalog, JdbcSetting<String> schema,
                                   JdbcSetting<Integer> holdability) {
        private static ConnectionState capture(Connection connection) throws SQLException {
            return new ConnectionState(connection.getAutoCommit(), JdbcSetting.capture(connection::isReadOnly),
                    JdbcSetting.capture(connection::getTransactionIsolation), JdbcSetting.capture(connection::getCatalog),
                    JdbcSetting.capture(connection::getSchema), JdbcSetting.capture(connection::getHoldability));
        }

        private void restore(Connection connection) throws SQLException {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            if (connection.getAutoCommit() != autoCommit) {
                connection.setAutoCommit(autoCommit);
            }
            readOnly.restore(connection::isReadOnly, connection::setReadOnly);
            isolation.restore(connection::getTransactionIsolation, connection::setTransactionIsolation);
            catalog.restore(connection::getCatalog, connection::setCatalog);
            schema.restore(connection::getSchema, connection::setSchema);
            holdability.restore(connection::getHoldability, connection::setHoldability);
            connection.clearWarnings();
        }
    }

    /** 可选 JDBC 属性仅在驱动支持读取时记录；已支持的属性重置失败即淘汰连接。 */
    private record JdbcSetting<T>(boolean supported, T value) {
        private static <T> JdbcSetting<T> capture(SqlGetter<T> getter) throws SQLException {
            try {
                return new JdbcSetting<>(true, getter.get());
            } catch (SQLFeatureNotSupportedException | AbstractMethodError e) {
                return new JdbcSetting<>(false, null);
            }
        }

        private void restore(SqlGetter<T> getter, SqlSetter<T> setter) throws SQLException {
            try {
                if (supported && !Objects.equals(value, getter.get())) {
                    setter.set(value);
                }
            } catch (AbstractMethodError e) {
                throw new SQLFeatureNotSupportedException("驱动无法恢复 JDBC 会话属性", e);
            }
        }
    }

    @FunctionalInterface
    private interface SqlGetter<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlSetter<T> {
        void set(T value) throws SQLException;
    }

    /**
     * 连接池构建器
     *
     * <p>支持链式配置所有参数，必填参数为 url。
     */
    public static class Builder {

        /**
         * JDBC URL（必填）
         */
        private String url;

        /**
         * 用户名
         */
        private String username;

        /**
         * 密码
         */
        private String password;

        /**
         * JDBC 驱动属性
         */
        private Properties driverProperties;

        /**
         * 验证 SQL
         */
        private String validationQuery = "SELECT 1";

        /**
         * 初始化 SQL 列表
         */
        private String[] initSqls;

        /**
         * 自动提交设置
         */
        private Boolean defaultAutoCommit;

        /**
         * 事务隔离级别
         */
        private Integer defaultTransactionIsolation;

        /**
         * 池最大容量
         */
        // [P3C A20 豁免]
        private int maxTotal = 10;

        /**
         * 最大空闲数
         */
        private int maxIdle;

        /**
         * 最小空闲数（预热）
         */
        private int minIdle = 0;

        /**
         * 借出超时（毫秒）
         */
        // [P3C A20 豁免]
        private long borrowTimeoutMillis = 3000;

        /**
         * 空闲超时（毫秒）
         */
        // [P3C A20 豁免]
        private long idleTimeoutMillis = 600000;

        /**
         * 空闲检测间隔（毫秒）
         */
        // [P3C A20 豁免]
        private long idleEvictionIntervalMillis = 30000;

        /**
         * 借出时验证
         */
        private boolean testOnBorrow = true;

        /**
         * 归还时验证
         */
        private boolean testOnReturn = true;

        /**
         * Builder
         * @return 返回值
        */
        Builder() {
        }

        /**
         * 设置 JDBC URL（必填）
         *
         * @param url JDBC 连接 URL
         * @return 当前 Builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 设置用户名
         *
         * @param username 数据库用户名
         * @return 当前 Builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置密码
         *
         * @param password 数据库密码
         * @return 当前 Builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置 JDBC 驱动属性
         *
         * @param properties 驱动属性（如 useSSL、serverTimezone 等）
         * @return 当前 Builder
         */
        public Builder driverProperties(Properties properties) {
            this.driverProperties = properties;
            return this;
        }

        /**
         * 设置验证 SQL
         *
         * <p>借出/归还连接时执行此 SQL 检测连接有效性。默认 "SELECT 1"。
         *
         * @param validationQuery 验证 SQL
         * @return 当前 Builder
         */
        public Builder validationQuery(String validationQuery) {
            this.validationQuery = validationQuery;
            return this;
        }

        /**
         * 设置初始化 SQL 列表
         *
         * <p>每个新连接创建后按顺序执行这些 SQL（如 SET NAMES、SET schema 等）。
         *
         * @param initSqls 初始化 SQL 数组
         * @return 当前 Builder
         */
        public Builder initSqls(String... initSqls) {
            this.initSqls = initSqls;
            return this;
        }

        /**
         * 设置默认自动提交
         *
         * @param autoCommit true=自动提交, false=手动提交, null=不设置
         * @return 当前 Builder
         */
        public Builder defaultAutoCommit(Boolean autoCommit) {
            this.defaultAutoCommit = autoCommit;
            return this;
        }

        /**
         * 设置默认事务隔离级别
         *
         * @param level Connection.TRANSACTION_* 常量
         * @return 当前 Builder
         */
        public Builder defaultTransactionIsolation(Integer level) {
            this.defaultTransactionIsolation = level;
            return this;
        }

        /**
         * 设置池最大容量
         *
         * @param maxTotal 最大容量
         * @return 当前 Builder
         */
        public Builder maxTotal(int maxTotal) {
            this.maxTotal = maxTotal;
            return this;
        }

        /**
         * 设置最大空闲数
         *
         * @param maxIdle 最大空闲数
         * @return 当前 Builder
         */
        public Builder maxIdle(int maxIdle) {
            this.maxIdle = maxIdle;
            return this;
        }

        /**
         * 设置最小空闲数（预热）
         *
         * @param minIdle 最小空闲数
         * @return 当前 Builder
         */
        public Builder minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        /**
         * 设置借出超时（毫秒）
         *
         * @param borrowTimeoutMillis 超时时间
         * @return 当前 Builder
         */
        public Builder borrowTimeoutMillis(long borrowTimeoutMillis) {
            this.borrowTimeoutMillis = borrowTimeoutMillis;
            return this;
        }

        /**
         * 设置空闲超时（毫秒）
         *
         * @param idleTimeoutMillis 超时时间
         * @return 当前 Builder
         */
        public Builder idleTimeoutMillis(long idleTimeoutMillis) {
            this.idleTimeoutMillis = idleTimeoutMillis;
            return this;
        }

        /**
         * 设置空闲检测间隔（毫秒）
         *
         * @param intervalMillis 检测间隔
         * @return 当前 Builder
         */
        public Builder idleEvictionIntervalMillis(long intervalMillis) {
            this.idleEvictionIntervalMillis = intervalMillis;
            return this;
        }

        /**
         * 设置借出时是否验证
         *
         * @param testOnBorrow 是否验证
         * @return 当前 Builder
         */
        public Builder testOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
            return this;
        }

        /**
         * 设置归还时是否验证
         *
         * @param testOnReturn 是否验证
         * @return 当前 Builder
         */
        public Builder testOnReturn(boolean testOnReturn) {
            this.testOnReturn = testOnReturn;
            return this;
        }

        /**
         * 构建连接池
         *
         * @return ConnectionPool 实例
         */
        public ConnectionPool build() {
            if (StringUtils.isBlank(url)) {
                throw new IllegalArgumentException("JDBC URL 不能为空");
            }
            return new ConnectionPool(this);
        }

        ObjectPoolConfig buildConfig() {
            return ObjectPoolConfig.builder()
                    .maxTotal(maxTotal)
                    .maxIdle(maxIdle > 0 ? maxIdle : maxTotal)
                    .minIdle(minIdle)
                    .borrowTimeoutMillis(borrowTimeoutMillis)
                    .idleTimeoutMillis(idleTimeoutMillis)
                    .idleEvictionIntervalMillis(idleEvictionIntervalMillis)
                    .testOnBorrow(testOnBorrow)
                    .testOnReturn(testOnReturn)
                    .build();
        }
    }
}
