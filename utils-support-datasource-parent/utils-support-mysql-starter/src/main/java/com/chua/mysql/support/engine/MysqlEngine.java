package com.chua.mysql.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.datasource.support.dialect.MysqlDialect;
import com.chua.datasource.support.engine.JdbcEngine;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * MySQL 数据库引擎。
 * <p>
 * 继承自 {@link JdbcEngine}，提供 MySQL 特有的便捷数据源配置方法。
 * 使用 HikariCP 连接池，默认最大连接数为 10。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * MysqlEngine engine = new MysqlEngine();
 * engine.addDataSource("default", "localhost", 3306, "mydb", "root", "password");
 * List<User> users = engine.query(User.class).list();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Spi("mysql")
public class MysqlEngine extends JdbcEngine {

    /**
     * 添加一个 MySQL 数据源。
     * <p>
     * 自动配置 JDBC URL、用户名、密码和连接池大小。
     * </p>
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
        return addDataSource(name, host, port, database, username, password, null);
    }

    public Engine addDataSource(String name, String host, int port, String database, String username, String password, Tunnel tunnel) {
        HikariDataSource ds = new HikariDataSource();
        final int[] tunnelPortCapture = {0};
        int targetPort = port;
        String targetHost = host;

        if (tunnel != null) {
            int tunnelPort = tunnel.open();
            if (tunnelPort > 0) {
                targetPort = tunnelPort;
                targetHost = "127.0.0.1";
                tunnelPortCapture[0] = tunnelPort;
            }
        }

        ds.setJdbcUrl("jdbc:mysql://" + targetHost + ":" + targetPort + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(10);
        return addDataSource(name, new EngineDataSource<HikariDataSource>() {
            @Override public String name() { return name; }
            @Override public HikariDataSource getSource() { return ds; }
            @Override public EngineDataSource<HikariDataSource> setSource(Object source) { return this; }
            @Override public Dialect getDialect() { return new MysqlDialect(); }
            @Override public EngineDataSource<HikariDataSource> setDialect(Dialect dialect) { return this; }
            @Override public int tunnelPort() { return tunnelPortCapture[0]; }
            @Override public EngineDataSource<HikariDataSource> setTunnelPort(int tunnelPort) { return this; }
            @Override public String url() { return ds.getJdbcUrl(); }
            @Override public String username() { return ds.getUsername(); }
            @Override public String password() { return ds.getPassword(); }
            @Override public void close() { if (ds instanceof AutoCloseable c) { try { c.close(); } catch (Exception ignored) {} } }
        });
    }
}
