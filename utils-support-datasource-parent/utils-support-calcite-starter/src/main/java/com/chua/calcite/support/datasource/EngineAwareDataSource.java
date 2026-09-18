package com.chua.calcite.support.datasource;

import com.chua.datasource.support.datasource.DataScheme;

import com.chua.common.support.reflection.ReflectUtils;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
* 包装统一 Calcite {@link DataSource}：拦截简单 更新 并路由到 Engine。
*
* @author CH
* @since 4.0.0.42
 */
public final class EngineAwareDataSource implements DataSource {

    /**
    * 委托的真实数据源
    */
    private final DataSource delegate;

    /**
    * 更新 路由执行器
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
    /** 获取Connection */
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    /** 获取Connection */
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    /**
    * wrapconnection
    *
    * @param conn conn
    * @return wrapConnection的结果
    */
    private Connection wrapConnection(Connection conn) {
        return (Connection) ReflectUtils.newProxy(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionHandler(conn, updateExecutor)
        );
    }

    private static final class ConnectionHandler implements InvocationHandler {
        /** 目标 */
        private final Connection target;
        /** 更新执行器 */
        private final EngineUpdateSqlExecutor updateExecutor;

        ConnectionHandler(Connection target, EngineUpdateSqlExecutor updateExecutor) {
            this.target = target;
            this.updateExecutor = updateExecutor;
        }

        @Override
        /** 调用 */
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("createStatement".equals(name)) {
                Statement st = (Statement) ReflectUtils.invoke(target, method.getName(), Statement.class, method.getParameterTypes(), args);
                return wrapStatement(st);
            }
            if ("prepareStatement".equals(name) && args != null && args.length >= 1 && args[0] instanceof String sql) {
                Integer routed = updateExecutor.tryExecute(sql);
                if (routed != null) {
 // 预编译 更新：返回只执行已计算结果的代理
                    return fixedUpdatePreparedStatement(routed);
                }
            }
            try {
                return ReflectUtils.invoke(target, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
        * wrap对账单
        *
        * @param st st
        * @return wrap对账单的结果
        */
        private Statement wrapStatement(Statement st) {
            return (Statement) ReflectUtils.newProxy(
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
                            return ReflectUtils.invoke(st, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
            );
        }

        /**
        * Fixed更新prepared对账单
        *
        * @param rows rows
        * @return fixed更新prepared对账单的结果
        */
        private Object fixedUpdatePreparedStatement(int rows) {
            return ReflectUtils.newProxy(
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
    /** 获取记录日志Writer */
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    /** 设置记录日志Writer */
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    /** 设置login超时 */
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    /** 获取login超时 */
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    /** 获取父日志记录器 */
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    /** Unwrap */
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    /** 是否包装器for */
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
