package com.chua.calcite.support.datasource;

import com.chua.datasource.support.datasource.DataScheme;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * 包装统一 Calcite {@link DataSource}：拦截简单 UPDATE 并路由到 Engine。
 *
 * @since 4.0.0.42
 */
public final class EngineAwareDataSource implements DataSource {

    /**
     * 委托的真实数据源
     */
    private final DataSource delegate;

    /**
     * UPDATE 路由执行器
     */
    private final EngineUpdateSqlExecutor updateExecutor;

    /**
     * 构造引擎感知数据源。
     *
     * @param delegate 真实数据源
     * @param schemes  引擎方案列表
     */
    public EngineAwareDataSource(DataSource delegate, List<DataScheme> schemes) {
        this.delegate = delegate;
        this.updateExecutor = new EngineUpdateSqlExecutor(schemes);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    private Connection wrapConnection(Connection conn) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionHandler(conn, updateExecutor)
        );
    }

    private static final class ConnectionHandler implements InvocationHandler {
        /** 目标 */
        private final Connection target;
        /** Update执行器 */
        private final EngineUpdateSqlExecutor updateExecutor;

        ConnectionHandler(Connection target, EngineUpdateSqlExecutor updateExecutor) {
            this.target = target;
            this.updateExecutor = updateExecutor;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("createStatement".equals(name)) {
                Statement st = (Statement) method.invoke(target, args);
                return wrapStatement(st);
            }
            if ("prepareStatement".equals(name) && args != null && args.length >= 1 && args[0] instanceof String sql) {
                Integer routed = updateExecutor.tryExecute(sql);
                if (routed != null) {
                    // 预编译 UPDATE：返回只执行已计算结果的代理
                    return fixedUpdatePreparedStatement(routed);
                }
            }
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        private Statement wrapStatement(Statement st) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class[]{Statement.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if (("executeUpdate".equals(m) || "execute".equals(m) || "executeLargeUpdate".equals(m))
                                && args != null && args.length >= 1 && args[0] instanceof String sql) {
                            Integer routed = updateExecutor.tryExecute(sql);
                            if (routed != null) {
                                if ("execute".equals(m)) {
                                    return false;
                                }
                                if ("executeLargeUpdate".equals(m)) {
                                    return (long) routed;
                                }
                                return routed;
                            }
                        }
                        try {
                            return method.invoke(st, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause() != null ? e.getCause() : e;
                        }
                    }
            );
        }

        private Object fixedUpdatePreparedStatement(int rows) {
            return Proxy.newProxyInstance(
                    java.sql.PreparedStatement.class.getClassLoader(),
                    new Class[]{java.sql.PreparedStatement.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("executeUpdate".equals(m)) {
                            return rows;
                        }
                        if ("executeLargeUpdate".equals(m)) {
                            return (long) rows;
                        }
                        if ("execute".equals(m)) {
                            return false;
                        }
                        if ("close".equals(m) || "clearParameters".equals(m) || m.startsWith("set")) {
                            return null;
                        }
                        if ("getUpdateCount".equals(m)) {
                            return rows;
                        }
                        if ("getResultSet".equals(m) || "getConnection".equals(m)) {
                            return null;
                        }
                        if ("isClosed".equals(m)) {
                            return false;
                        }
                        throw new SQLFeatureNotSupportedException(
                                "Engine 路由 UPDATE 的 PreparedStatement 仅支持 executeUpdate: " + m);
                    }
            );
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
