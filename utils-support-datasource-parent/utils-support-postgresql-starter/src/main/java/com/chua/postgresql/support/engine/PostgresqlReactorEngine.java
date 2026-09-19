package com.chua.postgresql.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;
import com.chua.datasource.support.url.JdbcUrl;

/**
 * PostgreSQL 响应式引擎，对应同步侧 {@link PostgresqlEngine}。
 *
 * <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
 * 复用 {@link PostgresqlEngine} 的数据源配置能力，提供响应式访问入口。
 * 如需真正非阻塞路径（含 R2DBC {@code $n} 占位符），可直接使用父类的
 * {@code addDataSource(name, "jdbc:postgresql://...", user, pwd)}
 * 自动转换为 R2DBC 连接（需 类路径 存在 R2dbc-PostgreSQL 驱动）。</p>
 *
 * <pre>{@code
 * PostgresqlReactorEngine engine = new PostgresqlReactorEngine();
 * engine.addDataSource("default", "localhost", 5432, "testdb", "postgres", "password");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("postgresql")
public class PostgresqlReactorEngine extends JdbcReactorEngine {

    /**
     * 创建 PostgreSQL 响应式引擎，内部持有同步 {@link PostgresqlEngine}。
     */
    public PostgresqlReactorEngine() {
        super(new PostgresqlEngine());
    }

    /**
     * 添加一个 PostgreSQL 数据源（委托给同步引擎）。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public PostgresqlReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        JdbcUrl.checkHost(host);
        JdbcUrl.checkPort(port);
        JdbcUrl.checkDatabase(database);
        ((PostgresqlEngine) delegate).addDataSource(name, host, port, database, username, password);
        // 注册到响应式 JDBC 路径（boundedElastic 上执行），与同步引擎共用同一库
        registerJdbcDataSource(name,
                "jdbc:postgresql://" + host + ":" + port + "/" + database,
                username, password);
        return this;
    }
}
