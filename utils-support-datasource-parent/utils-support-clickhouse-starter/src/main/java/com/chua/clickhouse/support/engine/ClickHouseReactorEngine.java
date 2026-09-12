package com.chua.clickhouse.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;

/**
   * click房子 响应式引擎，对应同步侧 {@link ClickHouseEngine}。
 *
 * <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
 * 复用 {@link ClickHouseEngine} 的数据源配置能力，提供响应式访问入口。
 * 如需真正非阻塞路径，可直接使用父类的
 * {@code addDataSource(name, "jdbc:clickhouse://...", user, pwd)}
   * 自动转换为 R2DBC 连接（需 类路径 存在 clickhouse-R2dbc 驱动）。</p>
 *
 * <pre>{@code
 * ClickHouseReactorEngine engine = new ClickHouseReactorEngine();
 * engine.addDataSource("default", "localhost", 8123, "default", "default", "");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>).eq(User::getName, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("clickhouse")
public class ClickHouseReactorEngine extends JdbcReactorEngine {

    /**
      * 创建 click房子 响应式引擎，内部持有同步 {@link ClickHouseEngine}。
     */
    public ClickHouseReactorEngine() {
        super(new ClickHouseEngine());
    }

    /**
      * 添加一个 click房子 数据源（委托给同步引擎）。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号（HTTP 端口，默认 8123）
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public ClickHouseReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        ((ClickHouseEngine) delegate).addDataSource(name, host, port, database, username, password);
        // 注册到响应式 JDBC 路径（boundedElastic 上执行），与同步引擎共用同一库
        registerJdbcDataSource(name,
                "jdbc:clickhouse://" + host + ":" + port + "/" + database,
                username, password);
        return this;
    }
}
