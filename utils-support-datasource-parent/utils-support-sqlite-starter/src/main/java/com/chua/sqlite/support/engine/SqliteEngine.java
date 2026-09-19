package com.chua.sqlite.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.dialect.SqliteDialect;
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
        ds.setJdbcUrl("jdbc:sqlite:" + filePath);
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(5);
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
                return this;
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
