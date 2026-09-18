package com.chua.sqlite.support.engine;

import com.chua.common.support.spi.annotations.Spi;

/**
 * SQLite 响应式引擎，基于 JdbcReactorEngine 实现。
 *
 * <p>提供非阻塞的 Reactor 风格查询 API，底层通过 HikariCP 连接池执行 JDBC 操作。</p>
 *
 * <pre>{@code
 * SqliteReactorEngine engine = new SqliteReactorEngine();
 * engine.addDataSource("mydb", "data/mydb.sqlite");
 * Flux<Map<String, Object>> rows = engine.query("SELECT * FROM users");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("sqlite")
public class SqliteReactorEngine extends com.chua.datasource.support.engine.JdbcReactorEngine {

    /**
    * 添加一个 SQLite 数据源。
    *
    * @param name     数据源名称
    * @param filePath SQLite 数据库文件路径
    * @return 当前引擎实例（支持链式调用）
    */
    public SqliteReactorEngine addDataSource(String name, String filePath) {
        registerJdbcDataSource(name, "jdbc:sqlite:" + filePath, null, null);
        return this;
    }
}
