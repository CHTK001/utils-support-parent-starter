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
 * <p>用途：官方 {@code org.sqlite.JDBC}（自 3.53 起是模板基类）在 agent 场景下会在
 * initialize 阶段重新定义自身并链接 {@code java.sql.Driver}，此时抛出
 * {@code NoClassDefFoundError: java/sql/Driver}，导致驱动注册失败。
 * 本类自身直接实现 {@link Driver}，注册稳定；实际建连委托给官方
 * {@code org.sqlite.SQLiteDataSource}，因此不重复实现数据库逻辑。</p>
 *
 * <p><b>命名约束</b>：本类的简单名一旦与 agent 进程里曾加载失败的旧名相同
 * （如 {@code ApmSqliteDriver}、{@code SqliteDriver}），链接 {@code java.sql.Driver}
 * 会稳定抛 {@code NoClassDefFoundError}，与字节码和 ClassLoader 无关；
 * 换名即可恢复。修改类名时务必在真实 {@code -javaagent} 场景下回归验证。</p>
 *
 * <p>本类<b>不使用</b> {@code org.sqlite} 包名：该包名会被官方驱动的 child-first
 * 规则接管，若同名会把官方 {@code JDBC} 类遮住，反而破坏 {@code SQLiteDataSource}
 * 的解析。</p>
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
     * 官方数据源类名（由 {@code sqlite-jdbc} 提供）
     */
    private static final String DATA_SOURCE = "org.sqlite.SQLiteDataSource";

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
            Class<?> type = Class.forName(DATA_SOURCE, true, ApmSqliteBridgeDriver.class.getClassLoader());
            Object source = type.getDeclaredConstructor().newInstance();
            type.getMethod("setUrl", String.class).invoke(source, url);
            if (info != null) {
                for (String key : info.stringPropertyNames()) {
                    try {
                        type.getMethod("setProperty", String.class, String.class)
                                .invoke(source, key, info.getProperty(key));
                    } catch (NoSuchMethodException ignored) {
                        // 该配置项官方数据源不支持，交给默认值
                    }
                }
            }
            return ((javax.sql.DataSource) source).getConnection();
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
