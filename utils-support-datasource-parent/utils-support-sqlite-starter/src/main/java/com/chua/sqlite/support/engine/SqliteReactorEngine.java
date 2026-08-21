package com.chua.sqlite.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;

/**
 * SQLite 响应式引擎，对应同步侧 {@link SqliteEngine}。
 *
 * <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
 * 复用 {@link SqliteEngine} 的数据源配置能力，提供响应式访问入口。</p>
 *
 * <pre>{@code
 * SqliteReactorEngine engine = new SqliteReactorEngine();
 * engine.addDataSource("default", "data/mydb.sqlite");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("sqlite")
public class SqliteReactorEngine extends JdbcReactorEngine {

    /**
     * 创建 SQLite 响应式引擎，内部持有同步 {@link SqliteEngine}。
     */
    public SqliteReactorEngine() {
        super(new SqliteEngine());
    }

    /**
     * 添加一个 SQLite 数据源（委托给同步引擎）。
     *
     * @param name     数据源名称
     * @param filePath SQLite 数据库文件路径
     * @return 当前引擎实例
     */
    public SqliteReactorEngine addDataSource(String name, String filePath) {
        ((SqliteEngine) delegate).addDataSource(name, filePath);
        return this;
    }
}