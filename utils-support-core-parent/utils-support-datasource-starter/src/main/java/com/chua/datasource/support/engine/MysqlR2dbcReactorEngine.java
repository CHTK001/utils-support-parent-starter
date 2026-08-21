package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.dialect.MysqlDialect;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;

/**
 * MySQL R2DBC 响应式引擎，对应同步侧 {@link com.chua.mysql.support.engine.MysqlEngine}。
 *
 * <p>使用 R2DBC MySQL 驱动实现真正的异步非阻塞数据库访问。
 * 原生 SQL 查询/更新/批量操作均返回 {@link reactor.core.publisher.Flux} / {@link reactor.core.publisher.Mono}
 * 非阻塞发布者，直接通过 R2DBC {@link ConnectionFactory} 执行，底层不经过
 * {@code boundedElastic} 调度阻塞。</p>
 *
 * <pre>{@code
 * // SPI 创建
 * ReactEngine engine = ReactEngine.create("mysql");
 * engine.addDataSource("default", host, port, database, username, password);
 *
 * // Lambda 链式查询
 * Flux<User> users = engine.query(User.class)
 *     .eq(User::getName, "张三")
 *     .gt(User::getAge, 18)
 *     .list();
 *
 * // 原生 SQL — 真正非阻塞
 * Mono<Integer> affected = engine.execute("insert into user(name) values(?)", "test");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("mysql")
public class MysqlR2dbcReactorEngine extends AbstractR2dbcReactorEngine {

    /**
     * 无参构造（SPI 使用）。
     */
    public MysqlR2dbcReactorEngine() {
        super(null, new MysqlDialect());
    }

    /**
     * 指定 R2DBC 连接工厂构造。
     *
     * @param factory 连接工厂
     */
    public MysqlR2dbcReactorEngine(ConnectionFactory factory) {
        super(factory, new MysqlDialect());
    }

    /**
     * 添加 MySQL 数据源。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return this
     */
    public MysqlR2dbcReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        ConnectionFactory factory = createFactory(host, port, database, username, password);
        return (MysqlR2dbcReactorEngine) super.addDataSource(name, factory);
    }

    /**
     * 构建 MySQL R2DBC 连接工厂。
     *
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 连接工厂
     */
    private static ConnectionFactory createFactory(String host, int port, String database, String username, String password) {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(DRIVER, "mysql")
                .option(HOST, host)
                .option(PORT, port)
                .option(DATABASE, database)
                .option(USER, username)
                .option(PASSWORD, password)
                .build();
        return ConnectionFactories.get(options);
    }

    /**
     * 通过 R2DBC URL 添加 MySQL 数据源。
     *
     * @param name      数据源名称
     * @param url       R2DBC URL（如 r2dbc:mysql://localhost:3306/mydb）
     * @param username  用户名
     * @param password  密码
     * @param dialect   方言
     * @return this
     */
    public MysqlR2dbcReactorEngine addDataSource(String name, String url, String username, String password, Dialect dialect) {
        ConnectionFactory factory = ConnectionFactories.get(url + "?user=" + username + "&password=" + password);
        return (MysqlR2dbcReactorEngine) super.addDataSource(name, factory, dialect);
    }
}