package com.chua.duckdb.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.dialect.DuckdbDialect;
import com.chua.datasource.support.engine.JdbcEngine;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
 * }</pre>
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
        Dialect dialect = new DuckdbDialect();
        return addDataSource(name, new EngineDataSource<DataSource>() {
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
                return this;
            }

            @Override
            public Dialect getDialect() {
                return dialect;
            }

            @Override
            public EngineDataSource<DataSource> setDialect(Dialect dialect) {
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
     * 因此本数据源复用同一个底层连接，并通过动态代理屏蔽 {@code close()}，
     * 使 try-with-resources 释放时不会真正关闭底层连接、避免表结构丢失。</p>
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
         * 复用的底层连接（懒加载）
         */
        private Connection sharedConnection;

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
            return getSharedConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getSharedConnection();
        }

        /**
         * 获取复用的底层连接，不存在时懒加载创建。
         *
         * @return 屏蔽 close 的共享连接
         * @throws SQLException 连接创建失败
         */
        private Connection getSharedConnection() throws SQLException {
            if (sharedConnection == null || sharedConnection.isClosed()) {
                sharedConnection = DriverManager.getConnection(jdbcUrl, username, password);
            }
            return proxyCloseIgnored(sharedConnection);
        }

        /**
         * 用动态代理包装连接，拦截 {@code close()} 使其成为空操作。
         *
         * <p>其他方法全部委托给底层连接。</p>
         *
         * @param target 底层连接
         * @return 屏蔽 close 的连接代理
         */
        @SuppressWarnings("unchecked")
        private static Connection proxyCloseIgnored(Connection target) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        // 拦截 close()，避免释放底层连接
                        if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                            return null;
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
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
         * <p>通过 {@code EngineDataSource.close()} 触发（因 AutoCloseable 自动识别）。</p>
         */
        @Override
        public void close() {
            Connection conn = sharedConnection;
            sharedConnection = null;
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }
}
