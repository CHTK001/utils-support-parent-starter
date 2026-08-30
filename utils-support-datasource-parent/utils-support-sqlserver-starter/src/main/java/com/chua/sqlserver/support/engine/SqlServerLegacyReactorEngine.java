package com.chua.sqlserver.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;

/**
 * SQL Server 老版本兼容响应式引擎（SQL Server 2000/2005），使用 jTDS 驱动。
 *
 * <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
 * jTDS 无 R2DBC 驱动，无法实现真正非阻塞。
 *
 * <pre>{@code
 * SqlServerLegacyReactorEngine engine = new SqlServerLegacyReactorEngine();
 * engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sqlserver-legacy")
public class SqlServerLegacyReactorEngine extends JdbcReactorEngine {

    /** 对应的同步引擎，用于承载 jTDS 数据源配置 */
    private final SqlServerLegacyEngine delegate = new SqlServerLegacyEngine();

    /**
     * 添加一个 SQL Server 老版本数据源（委托给同步引擎）。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public SqlServerLegacyReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        ((SqlServerLegacyEngine) delegate).addDataSource(name, host, port, database, username, password);
        // 获取同步引擎生成的 JDBC URL 并注册到响应式 JDBC 路径
        com.chua.common.support.lang.datasource.engine.EngineDataSource<?> ds = delegate.getDataSource(name);
        if (ds != null) {
            registerJdbcDataSource(name, ds.url(), username, password);
        }
        return this;
    }
}
