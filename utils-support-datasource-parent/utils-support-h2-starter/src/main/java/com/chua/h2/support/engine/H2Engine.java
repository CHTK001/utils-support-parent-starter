package com.chua.h2.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcEngine;
import com.chua.h2.support.cleanup.H2CleanupPlugin;
import com.chua.h2.support.dialect.H2Dialect;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * H2 嵌入式数据库引擎，基于 {@link JdbcEngine}。
 * <p>
 * 提供 H2 特有的便捷数据源配置，支持内存库和文件库，
 * 同时通过 {@link com.chua.h2.support.meta.H2MetaSearch} 提供全文检索能力。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * H2Engine engine = new H2Engine();
 * engine.addDataSource("default", "jdbc:h2:mem:testdb");
 *
 * // 全文检索
 * engine.meta().search().create("product_idx")
 *     .field("title", "text")
 *     .field("price", "integer")
 *     .execute();
 *
 * // 测试清理
 * engine.cleanup().cleanupByPrefix("test_");
 * }</pre>p().cleanupByPrefix("test_");
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("h2")
public class H2Engine extends JdbcEngine {

    /**
     * 添加一个 H2 数据源。
     * <p>
     * 支持内存数据库（{@code jdbc:h2:mem:...}）和文件数据库（{@code jdbc:h2:file:...}）。
     * </p>
     *
     * @param name    数据源名称
     * @param jdbcUrl H2 JDBC 连接串
     * @return 当前引擎实例
     */
    public Engine addDataSource(String name, String jdbcUrl) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setDriverClassName("org.h2.Driver");
        ds.setMaximumPoolSize(5);
        EngineDataSource<Object> dataSource = new EngineDataSource<Object>() {
            @Override
            public String name() { return name; }
            @Override
            public Object getSource() { return ds; }
            @Override
            public <R> R getSource(Class<R> type) { return type.cast(ds); }
            @Override
            public EngineDataSource<Object> setSource(Object source) { return this; }
            @Override
            public Dialect getDialect() { return new H2Dialect(); }
            @Override
            public EngineDataSource<Object> setDialect(Dialect dialect) { return this; }
            @Override
            public String url() { return ds.getJdbcUrl(); }
            @Override
            public String username() { return ds.getUsername(); }
            @Override
            public String password() { return ds.getPassword(); }
        };
        return super.addDataSource(name, dataSource);
    }

    // ==================== 测试清理插件 ====================

    /**
     * 获取 H2 清理插件。
     * <p>
     * 用于测试环境清理数据：删除所有用户表和全文索引，重置版本记录表。
     * </p>
     * <pre>{@code
     * engine.cleanup().cleanupByPrefix("test_");  // 隔离测试前缀
     * engine.cleanup().truncateAll();             // 清空数据保留表结构
     * }</pre>/pre>
     *
     * @return H2CleanupPlugin 实例
     */
    public H2CleanupPlugin cleanup() {
        return new H2CleanupPlugin(this);
    }
}
