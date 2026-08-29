package com.chua.h2.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.dialect.H2Dialect;
import com.chua.datasource.support.engine.JdbcEngine;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * H2 嵌入式数据库引擎。
 * <p>
 * 继承自 {@link JdbcEngine}，提供 H2 特有的便捷数据源配置方法。
 * H2 是一种轻量级嵌入式关系型数据库，
 * 适合本地数据存储、测试环境和嵌入式应用。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * H2Engine engine = new H2Engine();
 * // 内存数据库
 * engine.addDataSource("default", "jdbc:h2:mem:testdb");
 * // 文件数据库
 * engine.addDataSource("file", "jdbc:h2:~/data/mydb");
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
            public String name() {
                return name;
            }

            @Override
            public Object getSource() {
                return ds;
            }

            @Override
            public <R> R getSource(Class<R> type) {
                return type.cast(ds);
            }

            @Override
            public EngineDataSource<Object> setSource(Object source) {
                return this;
            }

            @Override
            public Dialect getDialect() {
                return new H2Dialect();
            }

            @Override
            public EngineDataSource<Object> setDialect(Dialect dialect) {
                return this;
            }

            @Override
            public String url() {
                return ds.getJdbcUrl();
            }

            @Override
            public String username() {
                return ds.getUsername();
            }

            @Override
            public String password() {
                return ds.getPassword();
            }
        };
        return super.addDataSource(name, dataSource);
    }
}
