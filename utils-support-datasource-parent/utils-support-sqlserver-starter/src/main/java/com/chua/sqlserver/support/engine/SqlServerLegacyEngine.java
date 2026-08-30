package com.chua.sqlserver.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.DataSourceOptions;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.dialect.SqlServerDialect;
import com.chua.datasource.support.engine.JdbcEngine;
import com.zaxxer.hikari.HikariDataSource;

/**
 * SQL Server 老版本兼容引擎（SQL Server 2000/2005）。
 *
 * <p>使用 jTDS 驱动替代微软官方 mssql-jdbc，
 * 后者最低仅支持 SQL Server 2008 R2。jTDS 1.3.x 是社区维护最活跃的老版本兼容驱动。
 *
 * <p>注意：jTDS 不支持 SQL Server 2008 R2 的某些新特性（如 Always Encrypted、JSON 函数），
 * 仅用于老旧实例的兼容性接入。若目标为 2008 R2+，请使用 {@link SqlServerEngine}。
 *
 * <pre>{@code
 * SqlServerLegacyEngine engine = new SqlServerLegacyEngine();
 * engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
 * List<User> users = engine.query(User.class).list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sqlserver-legacy")
public class SqlServerLegacyEngine extends JdbcEngine {

    /** jTDS JDBC URL 前缀 */
    private static final String JTDS_URL_PREFIX = "jdbc:jtds:sqlserver://";

    /**
     * 添加一个 SQL Server 老版本（2000/2005）数据源，使用 jTDS 驱动。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public Engine addDataSource(String name, String host, int port, String database, String username, String password) {
        DataSourceOptions options = new DataSourceOptions(name, host, port, database, username, password, null);
        return addDataSource(options);
    }

    /**
     * 添加一个 SQL Server 老版本数据源（支持隧道穿透）。
     *
     * @param options 数据源选项
     * @return 当前引擎实例
     */
    public Engine addDataSource(DataSourceOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        HikariDataSource ds = new HikariDataSource();
        final int[] tunnelPortCapture = {0};
        int targetPort = options.port();
        String targetHost = options.host();

        Tunnel tunnel = options.tunnel();
        if (tunnel != null) {
            int tunnelPort = tunnel.open();
            if (tunnelPort > 0) {
                targetPort = tunnelPort;
                targetHost = "127.0.0.1";
                tunnelPortCapture[0] = tunnelPort;
            }
        }

        // jTDS URL：jdbc:jtds:sqlserver://host:port/database;selectMethod=cursor;domain=user
        String jdbcUrl = JTDS_URL_PREFIX + targetHost + ":" + targetPort + "/" + options.database()
                + ";selectMethod=cursor;domain=" + options.username();

        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(options.username());
        ds.setPassword(options.password());
        ds.setMaximumPoolSize(10);

        return addDataSource(options.name(), new EngineDataSource<HikariDataSource>() {
            @Override public String name() { return options.name(); }
            @Override public HikariDataSource getSource() { return ds; }
            @Override public EngineDataSource<HikariDataSource> setSource(Object source) { return this; }
            @Override public Dialect getDialect() { return new SqlServerDialect(); }
            @Override public EngineDataSource<HikariDataSource> setDialect(Dialect dialect) { return this; }
            @Override public int tunnelPort() { return tunnelPortCapture[0]; }
            @Override public EngineDataSource<HikariDataSource> setTunnelPort(int tunnelPort) { return this; }
            @Override public String url() { return ds.getJdbcUrl(); }
            @Override public String username() { return options.username(); }
            @Override public String password() { return options.password(); }
            @Override public void close() { if (ds instanceof AutoCloseable c) { try { c.close(); } catch (Exception ignored) {} } }
        });
    }
}
