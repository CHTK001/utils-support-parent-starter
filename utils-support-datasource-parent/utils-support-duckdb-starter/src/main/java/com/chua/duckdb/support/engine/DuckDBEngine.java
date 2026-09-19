package com.chua.duckdb.support.engine;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcEngine;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * DuckDB 嵌入式数据库引擎，基于 {@link JdbcEngine} 提供 JDBC 查询能力。
 *
 * <p>支持两种数据来源：</p>
 * <ul>
 *   <li><b>内存数据</b> — 通过 {@link #store(String, List)} 存入内存，用于本地过滤查询</li>
 *   <li><b>DuckDB 数据库</b> — 通过 {@link #addDataSource(String, String)} 指定 {@code jdbc:duckdb:} 连接串，
 *       查询、更新、删除均走真实 DuckDB SQL 执行</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DuckDBEngine engine = new DuckDBEngine();
 * // 连接内存 DuckDB
 * engine.addDataSource("default", "jdbc:duckdb:");
 * // 连接文件数据库
 * engine.addDataSource("file", "jdbc:duckdb:/path/to/data.duckdb");
 * // 用完关闭，释放该数据源唯一的共享连接
 * engine.close();
 * }</pre>
 *
 * <p>每个数据源内部只维护一条共享连接，{@link #close()} 关闭数据源时一并释放；
 * 关闭后引擎不再发放连接，取连接与使用旧连接都会抛出明确的
 * {@link IllegalStateException}。共享连接内的语句需由调用方按线程串行执行，
 * DuckDB 的单个连接不支撑并发查询。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("duckdb")
public class DuckDBEngine extends JdbcEngine {

    /**
     * 默认存储名称
     */
    private static final String DEFAULT_NAME = "default";

    /**
     * 日志记录器，用于上报共享连接关闭失败等生命周期异常。
     * <p>使用全限定名是因为 {@code java.util.logging.Logger} 已被
     * {@link DataSource#getParentLogger()} 占用。</p>
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DuckDBEngine.class);

    /**
     * 将数据按名称存储到引擎中。
     *
     * @param name 数据存储的名称（表名）
     * @param data 要存储的数据列表
     * @param <T>  数据类型
     * @return 当前引擎实例
     */
    public <T> DuckDBEngine store(String name, List<T> data) {
        dataStores.put(name, new ArrayList<>(data));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 将数据存储到默认位置。
     *
     * @param data 要存储的数据列表
     * @param <T>  数据类型
     * @return 当前引擎实例
     */
    public <T> DuckDBEngine store(List<T> data) {
        return store(DEFAULT_NAME, data);
    }

    /**
     * 添加一个 DuckDB 数据源（无用户名密码，适用于嵌入式内存库）。
     *
     * @param name    数据源名称
     * @param jdbcUrl DuckDB JDBC 连接串，如 {@code jdbc:duckdb:} 或 {@code jdbc:duckdb:/path/to/file.db}
     * @return 当前引擎实例
     */
    public Engine addDataSource(String name, String jdbcUrl) {
        return addDataSource(name, jdbcUrl, null, null);
    }

    /**
     * 添加一个 DuckDB 数据源，支持用户名与密码。
     *
     * @param name     数据源名称
     * @param jdbcUrl  DuckDB JDBC 连接串
     * @param username 用户名（可为 null）
     * @param password 密码（可为 null）
     * @return 当前引擎实例
     */
    public Engine addDataSource(String name, String jdbcUrl, String username, String password) {
        DataSource source = new DriverDataSource(jdbcUrl, username, password);
        return addDataSource(name, new EngineDataSource<DataSource>() {
            private Dialect dialect = Dialect.require("duckdb");
            @Override
            public String name() {
                return name;
            }

            @Override
            public DataSource getSource() {
                return source;
            }

            @Override
            public EngineDataSource<DataSource> setSource(Object source) {
                throw new UnsupportedOperationException("运行期不支持替换数据源对象，请重新调用 addDataSource 注册新数据源");
            }

            @Override
            public Dialect getDialect() {
                return dialect;
            }

            @Override
            public EngineDataSource<DataSource> setDialect(Dialect d) {
                if (d != null) {
                    this.dialect = d;
                }
                return this;
            }

            @Override
            public String url() {
                return jdbcUrl;
            }

            @Override
            public String username() {
                return username;
            }

            @Override
            public String password() {
                return password;
            }
        });
    }

    /**
     * 基于 {@link DriverManager} 的单连接数据源，将 JDBC URL 包装为 {@link javax.sql.DataSource}。
     *
     * <p>适用于 DuckDB 等仅提供 JDBC Driver、未提供 DataSource 实现的嵌入式数据库。</p>
     * <p>嵌入式内存库（如 {@code jdbc:duckdb:}）的每个连接是独立数据库实例，
     * 因此本数据源只维护一条底层共享连接，并通过动态代理屏蔽 {@code close()}，
     * 使 try-with-resources 释放时只丢弃句柄、不真正关闭底层连接，避免表结构丢失。</p>
     *
     * <p>生命周期约定：</p>
     * <ul>
     *   <li>共享连接懒加载创建，创建与释放均在 {@code synchronized} 内完成，
     *       并发取连接不会建立第二条无人关闭的连接；建连失败的 {@link SQLException} 原样抛出。</li>
     *   <li>{@link #close()} 由 {@code EngineDataSource.close()}（{@link AutoCloseable} 自动识别）
     *       在 {@code AbstractEngine.close()} 中触发，只关一次且幂等。</li>
     *   <li>关闭后再次取连接、或继续持有并使用旧句柄，都会抛出带明确原因的
     *       {@link IllegalStateException}，不会静默重建连接造成泄漏。</li>
     * </ul>
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class DriverDataSource implements DataSource, AutoCloseable {

        /**
         * JDBC 连接串
         */
        private final String jdbcUrl;

        /**
         * 用户名（可为 null）
         */
        private final String username;

        /**
         * 密码（可为 null）
         */
        private final String password;

        /**
         * 复用的底层连接（懒加载），仅在持有本实例锁时读写，为 null 表示尚未建立或已释放
         */
        private Connection sharedConnection;

        /**
         * 对外发放的共享连接句柄，与底层连接一一对应，屏蔽 {@code close()} 后只创建一次
         */
        private Connection sharedHandle;

        /**
         * 关闭标记，置位后不再建立或发放连接
         */
        private boolean closed;

        /**
         * 构造基于 DriverManager 的数据源。
         *
         * @param jdbcUrl  JDBC 连接串
         * @param username 用户名（可为 null）
         * @param password 密码（可为 null）
         */
        DriverDataSource(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return acquire();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return acquire();
        }

        /**
         * 取得唯一的共享连接，尚未建立时懒加载创建。
         *
         * <p>方法整体同步：无锁的懒加载会让并发调用各自建立连接并互相覆盖字段，
         * 被覆盖的那条连接再也无人关闭。</p>
         * <p>建连失败时 {@link SQLException} 原样抛出，字段保持未建立状态，
         * 调用方拿到的是真实的数据库错误而不是后续 {@code null} 连接引发的 NPE。</p>
         *
         * @return 屏蔽 {@code close()} 的共享连接句柄
         * @throws SQLException         建立底层连接失败
         * @throws IllegalStateException 数据源已关闭，拒绝再发放连接
         */
        private synchronized Connection acquire() throws SQLException {
            if (closed) {
                throw new IllegalStateException("DuckDB 数据源已关闭，不再发放共享连接: " + jdbcUrl);
            }
            Connection current = sharedConnection;
            if (current == null || current.isClosed()) {
                Connection opened = DriverManager.getConnection(jdbcUrl, username, password);
                sharedConnection = opened;
                sharedHandle = closeIgnoredHandle(opened);
            }
            return sharedHandle;
        }

        /**
         * 用动态代理包装底层连接，生成把 {@code close()} 视为空操作的共享连接句柄。
         *
         * <p>除 {@code close()} 外的方法委托给底层连接，并原样传出底层异常：
         * 委托必须自行拆包 {@link InvocationTargetException}，否则 {@code SQLException}
         * 会被反射工具吞成 {@code null}，建语句、执行失败时调用方只能看到 NPE。</p>
         * <p>句柄与底层连接同生共死：数据源关闭后，仍被调用方持有的句柄上的任何操作
         * 都会抛出 {@link IllegalStateException}，避免在已释放的连接上继续写入。</p>
         *
         * @param target 底层连接
         * @return 屏蔽 {@code close()} 的共享连接句柄
         */
        private Connection closeIgnoredHandle(Connection target) {
            return (Connection) ReflectUtils.newProxy(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                            return null;
                        }
                        synchronized (DriverDataSource.this) {
                            if (closed) {
                                if ("isClosed".equals(method.getName()) && method.getParameterCount() == 0) {
                                    return Boolean.TRUE;
                                }
                                throw new IllegalStateException(
                                        "DuckDB 共享连接已关闭，禁止继续使用: " + jdbcUrl);
                            }
                        }
                        return delegate(target, method, args);
                    });
        }

        /**
         * 把连接方法调用委托给底层连接，并还原其抛出的异常。
         *
         * @param target 底层连接
         * @param method 被调用的 {@link Connection} 接口方法
         * @param args   实参数组，无参方法为 null
         * @return 底层方法的返回值
         * @throws SQLException         底层方法抛出数据库异常
         * @throws RuntimeException     底层方法抛出的其它运行时异常
         */
        private static Object delegate(Connection target, Method method, Object[] args)
                throws SQLException {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new SQLException("调用 DuckDB 连接方法失败: " + method.getName(), cause);
            } catch (IllegalAccessException e) {
                throw new SQLException("无法调用 DuckDB 连接方法: " + method.getName(), e);
            }
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("DriverDataSource 不支持 getParentLogger");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("无法 unwrap 到类型: " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this);
        }

        /**
         * 关闭底层共享连接，释放资源。
         * <p>通过 {@code EngineDataSource.close()} 触发（因 AutoCloseable 自动识别），
         * 即 {@code AbstractEngine.close()} 是共享连接唯一的释放点。</p>
         * <p>先置关闭标记再释放连接，且整个操作与 {@link #acquire()} 互斥：
         * 否则关闭进行中的并发取连接会重建一条无人负责关闭的新连接。</p>
         * <p>重复调用为空操作，保证一条连接只关一次；底层关闭失败会被记录而不静默丢弃，
         * 因为此时连接可能仍占用着数据库文件。</p>
         */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            Connection conn = sharedConnection;
            sharedConnection = null;
            sharedHandle = null;
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.warn("关闭 DuckDB 共享连接失败, url={}", jdbcUrl, e);
                }
            }
        }
    }
}
