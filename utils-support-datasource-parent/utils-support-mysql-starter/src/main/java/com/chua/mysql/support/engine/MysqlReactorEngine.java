package com.chua.mysql.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;

/**
 * MySQL 响应式引擎，对应同步侧 {@link MysqlEngine}。
 *
 * <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
 * 复用 {@link MysqlEngine} 的数据源配置能力，提供响应式访问入口。</p>
 *
 * <pre>{@code
 * MysqlReactorEngine engine = new MysqlReactorEngine();
 * engine.addDataSource("default", "localhost", 3306, "mydb", "root", "password");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("mysql")
public class MysqlReactorEngine extends JdbcReactorEngine {

    /**
     * 创建 MySQL 响应式引擎，内部持有同步 {@link MysqlEngine}。
     */
    public MysqlReactorEngine() {
        super(new MysqlEngine());
    }

    /**
     * 添加一个 MySQL 数据源（委托给同步引擎）。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public MysqlReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        ((MysqlEngine) delegate).addDataSource(name, host, port, database, username, password);
        // 注册到响应式 JDBC 路径（boundedElastic 上执行），与同步引擎共用同一库
        registerJdbcDataSource(name,
                "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true",
                username, password);
        return this;
    }
}