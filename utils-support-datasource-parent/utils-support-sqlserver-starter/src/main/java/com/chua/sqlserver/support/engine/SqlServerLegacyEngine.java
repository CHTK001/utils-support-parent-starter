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

import java.util.Map;

/**
 * SQL 服务端 老版本兼容引擎（SQL 服务端 2000/2005）。
 *
 * <p>使用 jTDS 驱动替代微软官方 mssql-jdbc，
 * 后者最低仅支持 SQL 服务端 2008 R2。jtds 1.3.x 是社区维护最活跃的老版本兼容驱动。
 *
 * <p>jTDS URL 参数通过 {@link DataSourceOptions#jtdsUrlParams()} 传入，默认使用
 * {@code selectMethod=cursor}，不使用 NTLM 认证时 domain 不添加。
 *
 * <pre>{@code
 * // 默认配置（selectMethod=cursor，无 domain）
 * SqlServerLegacyEngine engine = new SqlServerLegacyEngine();
 * engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
 *
 * // NTLM 域认证
 * Map<String, String> params = Map.of("domain", "MYDOMAIN");
 * DataSourceOptions opts = new DataSourceOptions("default", "localhost", 1433, "master", "DOMAIN\\sa", "password", null, params);
 * engine.addDataSource(opts);
 * }</pre>"DOMAIN\\sa", "password", null, params);
 * engine.addDataSource(opts);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sqlserver-legacy")
public class SqlServerLegacyEngine extends JdbcEngine {

    /** jtds JDBC URL 前缀 */
    private static final String JTDS_URL_PREFIX = "jdbc:jtds:sqlserver://";

    /** jtds URL 默认参数：游标模式读取结果集 */
    private static final String DEFAULT_JTDS_SELECT_METHOD = "cursor";
    /** 默认最大连接数 */
    private static final int DEFAULT_MAX_POOL_SIZE = 10;

    /**
     * 添加一个 SQL Server 数据源（便捷重载）。
     * 将主机/端口/库名/账号等参数封装为 DataSourceOptions 后委托给主方法。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例（链式调用）
     */
    public Engine addDataSource(String name, String host, int port, String database, String username, String password) {
        DataSourceOptions options = new DataSourceOptions(name, host, port, database, username, password, null, null);
        return addDataSource(options);
    }

    /**
     * 添加数据来源。
     *
     * @param options 选项，不允许为 null
     * @return 引擎 对象
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

        // jTDS URL 参数从 options.jtdsUrlParams() 读取，避免硬编码
        Map<String, String> jtdsParams = options.jtdsUrlParams();
        StringBuilder urlBuilder = new StringBuilder(JTDS_URL_PREFIX)
                .append(targetHost).append(':').append(targetPort)
                .append('/').append(options.database());
        urlBuilder.append(";selectMethod=").append(jtdsParams.getOrDefault("selectMethod", DEFAULT_JTDS_SELECT_METHOD));
        String domain = jtdsParams.get("domain");
        if (domain != null && !domain.isEmpty()) {
            urlBuilder.append(";domain=").append(domain);
        }
        for (Map.Entry<String, String> entry : jtdsParams.entrySet()) {
            if (!"selectMethod".equals(entry.getKey()) && !"domain".equals(entry.getKey())) {
                urlBuilder.append(';').append(entry.getKey()).append('=').append(entry.getValue());
            }
        }

        ds.setJdbcUrl(urlBuilder.toString());
        ds.setUsername(options.username());
        ds.setPassword(options.password());
        ds.setMaximumPoolSize(DEFAULT_MAX_POOL_SIZE);

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
            @Override public void close() {
                if (ds instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}
