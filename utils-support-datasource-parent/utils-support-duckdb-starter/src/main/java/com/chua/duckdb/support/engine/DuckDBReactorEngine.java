package com.chua.duckdb.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;

/**
   * duckdb 响应式引擎，对应同步侧 {@link DuckDBEngine}。
 *
 * <p>当前为伪响应式实现（{@code boundedElastic} 调度阻塞 JDBC 调用），
 * 复用 {@link DuckDBEngine} 的数据源配置能力，提供响应式访问入口。</p>
 *
 * <pre>{@code
 * DuckDBReactorEngine engine = new DuckDBReactorEngine();
 * engine.addDataSource("default", "jdbc:duckdb:");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>me, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("duckdb")
public class DuckDBReactorEngine extends JdbcReactorEngine {

    /**
      * 创建 duckdb 响应式引擎，内部持有同步 {@link DuckDBEngine}。
     */
    public DuckDBReactorEngine() {
        super(new DuckDBEngine());
    }

    /**
      * 添加一个 duckdb 数据源（委托给同步引擎）。
     *
     * @param name    数据源名称
     * @param jdbcUrl duckdb JDBC 连接串
     * @return 当前引擎实例
     */
    public DuckDBReactorEngine addDataSource(String name, String jdbcUrl) {
        ((DuckDBEngine) delegate).addDataSource(name, jdbcUrl);
 // 注册纯 JDBC 数据源（duckdb 无 R2DBC 驱动），执行/查询 走 JDBC 路径
        registerJdbcDataSource(name, jdbcUrl, null, null);
        return this;
    }
}