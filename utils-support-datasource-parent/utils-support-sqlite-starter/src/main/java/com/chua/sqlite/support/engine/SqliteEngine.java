package com.chua.sqlite.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcEngine;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * sqlite 嵌入式数据库引擎。
 * <p>
 * 继承自 {@link JdbcEngine}，提供 sqlite 特有的便捷数据源配置方法。
 * sqlite 是一种轻量级、零配置的嵌入式关系型数据库，
 * 适合本地数据存储、测试环境和移动端应用。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * SqliteEngine engine = new SqliteEngine();
 * engine.addDataSource("default", "data/mydb.sqlite");
 * List<User> users = engine.query(User.class).list();
 * }</pre>r.class).list();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("sqlite")
public class SqliteEngine extends JdbcEngine {

    /**
     * 添加一个 sqlite 数据源。
     * <p>
     * 使用 hikaricp 连接池，最大连接数为 5。
     * </p>
     *
     * @param name     数据源名称
     * @param filePath sqlite 数据库文件路径
     * @return 当前引擎实例
     */
    public Engine addDataSource(String name, String filePath) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:" + toUrlPath(name, filePath));
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(5);
        // sqlite 是单写者模型，池内并发写会让后到的连接立刻 SQLITE_BUSY，busy_timeout 使其排队等待
        ds.addDataSourceProperty("busy_timeout", 5000);
        EngineDataSource<Object> dataSource = new EngineDataSource<Object>() {
            private com.chua.common.support.lang.datasource.dialect.Dialect dialect =
                    com.chua.common.support.lang.datasource.dialect.Dialect.require("sqlite");
            @Override
            /**
             * 名称
            */
            public String name() {
                return name;
            }

            @Override
            /**
             * 获取源
            */
            public Object getSource() {
                return ds;
            }

            @Override
            /**
             * 获取源
            */
            public <R> R getSource(Class<R> type) {
                return type.cast(ds);
            }

            @Override
            /**
             * 设置源
            */
            public EngineDataSource<Object> setSource(Object source) {
                throw new UnsupportedOperationException("运行期不支持替换数据源对象，请重新调用 addDataSource 注册新数据源");
            }

            @Override
            /**
             * 获取Dialect
            */
            public com.chua.common.support.lang.datasource.dialect.Dialect getDialect() {
                return dialect;
            }

            @Override
            /**
             * 设置Dialect
            */
            public EngineDataSource<Object> setDialect(com.chua.common.support.lang.datasource.dialect.Dialect dialect) {
                if (dialect != null) {
                    this.dialect = dialect;
                }
                return this;
            }

            @Override
            /**
             * Url
            */
            public String url() {
                return ds.getJdbcUrl();
            }

            @Override
            /**
             * 用户名
            */
            public String username() {
                return ds.getUsername();
            }

            @Override
            /**
             * 密码
            */
            public String password() {
                return ds.getPassword();
            }
        };
        return super.addDataSource(name, dataSource);
    }

    /**
     * 归一化 jdbc url 中的 sqlite 路径部分。
     * <p>
     * {@code :memory:} 是私有库：连接池里每条连接都会拿到一份独立的空库，写入的数据随机不可见，
     * 因此改写为按数据源命名的共享缓存库，使同一数据源的多个连接看到同一份数据。
     * </p>
     *
     * @param name     数据源名称
     * @param filePath 数据库文件路径，或 {@code :memory:}
     * @return 可拼入 jdbc url 的路径
     */
    private static String toUrlPath(String name, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("sqlite 数据库路径不能为空, 数据源: " + name);
        }
        if (!":memory:".equals(filePath)) {
            return filePath;
        }
        String key = (name == null ? "default" : name).replaceAll("[^A-Za-z0-9_]", "_");
        return "file:ch_mem_" + key + "?mode=memory&cache=shared";
    }
}
