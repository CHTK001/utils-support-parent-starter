package com.chua.runtime.apm.storage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SQLite 连接代理。
 *
 * <p><b>为什么需要它</b>：agent 把自身 jar 追加到 bootstrap 搜索路径后，只要调用栈里
 * 出现一个由 bootstrap 定义的栈帧，JVM 就会用 bootstrap 的可见性去解析<b>整个调用链</b>
 * 的符号引用；bootstrap 看不到 {@code java.sql}，于是抛
 * {@code NoClassDefFoundError: java/sql/Driver}。<br>
 * 因此所有和 JDBC 驱动打交道的方法（加载驱动、建连、每次 {@code equals} 判断）
 * 都不能含有 bootstrap 定义的栈帧，必须全部挂在隔离 ClassLoader 定义的类里。</p>
 *
 * <p><b>做法</b>：本类由隔离 ClassLoader 用 {@code defineClass} 注入，自身持有驱动实例，
 * 对外只暴露 {@link #connection(String)}、{@link #close()} 等不涉及 {@code javax.sql}
 * 的方法。调用方只能在隔离 ClassLoader 内收窄 {@code java.sql.Driver} 类型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SqliteDriverProxy {

    /**
     * 隔离 ClassLoader 内解析 {@code java.sql.Driver.connect(String, Properties)} 用到的签名常量。
     */
    private static final Class<?>[] CONNECT_SIG = {String.class, Properties.class};

    /**
     * 连接代理实例缓存：每个数据库文件复用同一个代理，避免 equals 反复进入 bootstrap 栈帧。
     */
    private static final ConcurrentMap<String, Object> CONNECTION_PROXIES = new ConcurrentHashMap<>();

    /**
     * 外部传入的代理对象（必须由隔离 ClassLoader 定义，否则不能触发它的逻辑）。
     */
    private static final ConcurrentMap<String, Object> INJECTED = new ConcurrentHashMap<>();

    /**
     * 驱动实例（调用方注入；本类不做类型收窄，避免引入额外解析点）。
     */
    private Object driver;

    /**
     * 官方驱动类名。
     */
    private String driverClassName;

    /**
     * 构造器。
     */
    public SqliteDriverProxy() {
    }

    /**
     * 注入驱动实例。
     *
     * <p>由 {@link ApmStorageClassLoader} 反射调用，返回值是后续 create 的 key。</p>
     *
     * @param key       标识（通常是驱动类名）
     * @param driver    驱动实例
     * @param driverCls 驱动类名（本类内部再加载时使用）
     * @return 注入 key
     */
    public static String inject(String key, Object driver, String driverCls) {
        SqliteDriverProxy handler = new SqliteDriverProxy();
        handler.driver = driver;
        handler.driverClassName = driverCls;
        INJECTED.put(key, handler);
        return key;
    }

    /**
     * 建连（不进入任何 bootstrap 栈帧）。
     *
     * @param url 连接地址
     * @return 连接
     * @throws SQLException 建连失败
     */
    public Connection connection(String url) throws SQLException {
        Object d = driver;
        if (d == null) {
            d = loadDriver(driverClassName);
        }
        try {
            Method connect = d.getClass().getMethod("connect", CONNECT_SIG);
            Object conn = connect.invoke(d, url, new Properties());
            if (conn instanceof Connection c) {
                return c;
            }
            throw new SQLException("驱动返回了非 JDBC 连接: " + conn);
        } catch (SQLException e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("连接失败: " + url + " (" + e.getCause() + ")", e.getCause());
        } catch (Throwable e) {
            throw new SQLException("连接失败: " + url + " (" + e + ")", e);
        }
    }

    /**
     * 加载官方驱动（在本类内部完成，调用方不接触驱动类）。
     *
     * @param name 驱动类名
     * @return 驱动实例
     * @throws SQLException 加载失败
     */
    private Object loadDriver(String name) throws SQLException {
        try {
            Class<?> type = Class.forName(name, true, SqliteDriverProxy.class.getClassLoader());
            return type.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            throw new SQLException("加载驱动失败: " + name + " (" + e + ")", e);
        }
    }

    /**
     * 为连接创建代理。
     *
     * <p>{@code DriverManager}/{@code conn.equals()} 等会在 bootstrap 栈帧里比较连接对象，
     * 那一步会因 {@code java/sql/Driver} 解析不到而失败；用代理把解析留在隔离 CL 内。</p>
     *
     * @param conn 真实连接
     * @return 代理连接
     */
    public static Connection proxy(Connection conn) {
        if (conn == null || Proxy.isProxyClass(conn.getClass())) {
            return conn;
        }
        String key = String.valueOf(System.identityHashCode(conn));
        Object cached = CONNECTION_PROXIES.get(key);
        if (cached instanceof Connection c) {
            return c;
        }
        SqliteConnectionHandler handler = new SqliteConnectionHandler(conn);
        ClassLoader loader = SqliteDriverProxy.class.getClassLoader();
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class}, handler);
        CONNECTION_PROXIES.put(key, proxy);
        return (Connection) proxy;
    }

    /**
     * 连接代理处理器。
     */
    private static final class SqliteConnectionHandler implements java.lang.reflect.InvocationHandler {

        /**
         * 真实连接。
         */
        private final Connection target;

        /**
         * 构造器。
         *
         * @param target 真实连接
         */
        private SqliteConnectionHandler(Connection target) {
            this.target = target;
        }

        /**
         * 连接的方法：{@code equals} 比较底层连接，其余直接委派。
         *
         * @param proxy  代理
         * @param method 方法
         * @param args   参数
         * @return 结果
         * @throws Throwable 失败
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("equals".equals(name)) {
                boolean same = proxy == (args == null ? null : args[0]);
                return same || target.equals(unwrap(args == null ? null : args[0]));
            }
            if ("hashCode".equals(name)) {
                return target.hashCode();
            }
            if ("toString".equals(name)) {
                return target.toString();
            }
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /**
         * 解出代理背后的真实连接。
         *
         * @param other 对象
         * @return 真实连接
         */
        private Object unwrap(Object other) {
            if (other instanceof Connection && Proxy.isProxyClass(other.getClass())) {
                Object handler = Proxy.getInvocationHandler(other);
                if (handler instanceof SqliteConnectionHandler sh) {
                    return sh.target;
                }
            }
            return other;
        }
    }

    /**
     * 驱动接口需要的方法（供隔离加载器内的存储实现直接调用）。
     *
     * @param url 连接地址
     * @return 连接
     * @throws SQLException 失败
     */
    public static Connection open(String url) throws SQLException {
        throw new IllegalStateException("请通过注入实例调用");
    }

    /**
     * 未使用——保留以避免编译器告警（占位）。
     *
     * @return 空
     */
    private static Object unused() {
        try {
            Constructor<?> c = SqliteDriverProxy.class.getDeclaredConstructor();
            return c;
        } catch (Exception e) {
            return null;
        }
    }
}
