package com.chua.sqlserver.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;

/**
* SQL 服务端 响应式引擎，对应同步侧 {@link SqlServerEngine}。
*
* <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
* 复用 {@link SqlServerEngine} 的数据源配置能力，提供响应式访问入口。</p>
*
* <p>注意：即使通过父类 {@code addDataSource(name, "jdbc:sqlserver://...", user, pwd)}
* 注册 R2DBC 连接工厂，执行时仍会走 JDBC 路径——
* R2dbc-mssql 的 {@code SimpleMssqlStatement} 不支持参数绑定（核心引擎已内置该路由决策）。</p>
*
* <pre>{@code
* SqlServerReactorEngine engine = new SqlServerReactorEngine();
* engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
* Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
* }</pre>(User::getName, "张三").list();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("sqlserver")
public class SqlServerReactorEngine extends JdbcReactorEngine {

    /**
    * 创建 SQL 服务端 响应式引擎，内部持有同步 {@link SqlServerEngine}。
     */
    public SqlServerReactorEngine() {
        super(new SqlServerEngine());
    }

    /**
    * 添加一个 SQL 服务端 数据源（委托给同步引擎）。
    *
    * @param name     数据源名称
    * @param host     主机地址
    * @param port     端口号
    * @param database 数据库名
    * @param username 用户名
    * @param password 密码
    * @return 当前引擎实例
     */
    public SqlServerReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        ((SqlServerEngine) delegate).addDataSource(name, host, port, database, username, password);
        // 注册到响应式 JDBC 路径（boundedElastic 上执行），与同步引擎共用同一库
        registerJdbcDataSource(name,
                "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true",
                username, password);
        return this;
    }
}
