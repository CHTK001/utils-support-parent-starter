package com.chua.runtime.apm.storage;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * APM 内部最小 SQLite JDBC 驱动（桥接驱动）。
 *
 * <p>实际建连委托给官方 {@code org.sqlite.JDBC}，因此不重复实现数据库逻辑。</p>
 *
 * <p><b>禁止引用 {@code javax.sql}</b>：本类在 agent 场景下由隔离 ClassLoader 定义，
 * 而 agent 把自身 jar 追加到 bootstrap 搜索路径后，JVM 链接任何依赖 {@code javax.sql}
 * 的类都会失败（实测 JDK 25 + sqlite-jdbc）。官方 {@code org.sqlite.SQLiteDataSource}
 * 正是这类类，所以这里只使用 {@code org.sqlite.JDBC}，并且只用 {@code java.sql}。</p>
 *
 * <p><b>命名约束</b>：修改类名时务必在真实 {@code -javaagent} 场景下回归验证。</p>
 *
 * <p>本类<b>不使用</b> {@code org.sqlite} 包名：该包名会被隔离 ClassLoader 的
 * child-first 规则接管，若同名会把官方 {@code JDBC} 类遮住。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ApmSqliteBridgeDriver implements Driver {

    /**
     * URL 前缀
     */
    private static final String PREFIX = "jdbc:sqlite:";

    /**
     * 官方驱动类名（由 {@code sqlite-jdbc} 提供，只依赖 {@code java.sql}）
     */
    private static final String OFFICIAL_DRIVER = "org.sqlite.JDBC";

    /**
     * 静态注册到 DriverManager（应用 classpath 场景下可直接 getConnection）
     */
    static {
        try {
            java.sql.DriverManager.registerDriver(new ApmSqliteBridgeDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 构造器。
     */
    public ApmSqliteBridgeDriver() {
    }

    /**
     * 建立连接
     *
     * @param url  连接地址
     * @param info 连接属性
     * @return 连接；URL 不被接受时返回 null
     * @throws SQLException 连接失败
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        try {
            ClassLoader loader = ApmSqliteBridgeDriver.class.getClassLoader();
            Class<?> type = Class.forName(OFFICIAL_DRIVER, true, loader);
            Driver delegate = (Driver) type.getDeclaredConstructor().newInstance();
            return delegate.connect(url, info == null ? new Properties() : info);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable e) {
            SQLException ex = new SQLException("SQLite 连接失败: " + url + " (" + e + ")");
            ex.initCause(e);
            throw ex;
        }
    }

    /**
     * 是否接受该 URL
     *
     * @param url 连接地址
     * @return 是否接受
     */
    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /**
     * 连接属性信息
     *
     * @param url  连接地址
     * @param info 连接属性
     * @return 属性描述
     */
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    /**
     * 主版本号
     *
     * @return 版本
     */
    @Override
    public int getMajorVersion() {
        return 3;
    }

    /**
     * 次版本号
     *
     * @return 版本
     */
    @Override
    public int getMinorVersion() {
        return 0;
    }

    /**
     * 是否完全遵循 JDBC
     *
     * @return 恒为 false
     */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    /**
     * 父日志器
     *
     * @return 日志器
     */
    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("com.chua.runtime.apm.storage.sqlite");
    }
}
