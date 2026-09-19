package com.chua.oracle.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.DataSourceOptions;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.dialect.OracleDialect;
import com.chua.datasource.support.engine.JdbcEngine;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Oracle 数据库引擎。
 *
 * <p>继承自 {@link JdbcEngine}，提供 Oracle 特有的便捷数据源配置方法。
 * 使用 hikaricp 连接池，默认最大连接数为 10。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * OracleEngine engine = new OracleEngine();
 * engine.addDataSource(new DataSourceOptions("default", "localhost", 1521, "orcl", "system", "password", null));
 * List<User> users = engine.query(User.class).list();
 * }</pre> users = engine.query(User.class).list();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("oracle")
public class OracleEngine extends JdbcEngine {

    /**
     * 添加一个 Oracle 数据源（服务 名称 方式）。
     *
     * <p>自动配置 JDBC URL、用户名、密码和连接池大小。</p>
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 服务名（服务 名称）
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public Engine addDataSource(String name, String host, int port, String database, String username, String password) {
        DataSourceOptions options = new DataSourceOptions(name, host, port, database, username, password, null);
        return addDataSource(options);
    }

    /**
     * 添加一个 Oracle 数据源（支持隧道穿透）。
     *
     * @param options 数据源选项，包含连接信息和可选隧道
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

        // 存在隧道时改走本地回环地址与隧道端口
        Tunnel tunnel = options.tunnel();
        if (tunnel != null) {
            int tunnelPort = tunnel.open();
            if (tunnelPort > 0) {
                targetPort = tunnelPort;
                targetHost = "127.0.0.1";
                tunnelPortCapture[0] = tunnelPort;
            }
        }

        ds.setJdbcUrl("jdbc:oracle:thin:@//" + targetHost + ":" + targetPort + "/" + options.database());
        ds.setUsername(options.username());
        ds.setPassword(options.password());
        ds.setMaximumPoolSize(10);
        return addDataSource(options.name(), new EngineDataSource<HikariDataSource>() {
            @Override public String name() { return options.name(); }
            @Override public HikariDataSource getSource() { return ds; }
            @Override public EngineDataSource<HikariDataSource> setSource(Object source) { return this; }
            @Override public Dialect getDialect() { return new OracleDialect(); }
            @Override public EngineDataSource<HikariDataSource> setDialect(Dialect dialect) { return this; }
            @Override public int tunnelPort() { return tunnelPortCapture[0]; }
            @Override public EngineDataSource<HikariDataSource> setTunnelPort(int tunnelPort) { return this; }
            @Override public String url() { return ds.getJdbcUrl(); }
            @Override public String username() { return ds.getUsername(); }
            @Override public String password() { return ds.getPassword(); }
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
