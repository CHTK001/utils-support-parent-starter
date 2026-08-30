package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.*;
import com.chua.common.support.lang.datasource.meta.model.*;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.common.support.spi.annotations.Spi;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MySQL 元数据入口。
 * <p>
 * 通过 SPI 机制注册为 MySQL 引擎的元数据实现。
 * 提供表、视图、索引、触发器、存储过程、外键、用户、权限的元数据操作能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@spi("mysql")
public class MysqlMetaData extends AbstractMetaData {

    public MysqlMetaData(Engine engine) {
        super(engine);
    }

    @Override
    public MetaTable table() {
        return new MysqlMetaTable(this, engine);
    }

    @Override
    public MetaTable table(String tableName) {
        return new MysqlMetaTable(this, engine, tableName);
    }

    @Override
    public MetaView view() {
        return new MysqlMetaView(this, engine);
    }

    @Override
    public MetaView view(String viewName) {
        return new MysqlMetaView(this, engine, viewName);
    }

    @Override
    public MetaIndex index() {
        return new MysqlMetaIndex(this, engine);
    }

    @Override
    public MetaIndex index(String indexName) {
        return new MysqlMetaIndex(this, engine, indexName);
    }

    @Override
    public MetaTrigger trigger() {
        return new MysqlMetaTrigger(this, engine);
    }

    @Override
    public MetaTrigger trigger(String triggerName) {
        return new MysqlMetaTrigger(this, engine, triggerName);
    }

    @Override
    public MetaProcedure procedure() {
        return new MysqlMetaProcedure(this, engine);
    }

    @Override
    public MetaProcedure procedure(String procedureName) {
        return new MysqlMetaProcedure(this, engine, procedureName);
    }

    @Override
    public MetaForeignKey fk() {
        return new MysqlMetaForeignKey(this, engine);
    }

    @Override
    public MetaForeignKey fk(String fkName) {
        return new MysqlMetaForeignKey(this, engine, fkName);
    }

    @Override
    public MetaUser user() {
        return new MysqlMetaUser(getJdbcDataSource());
    }

    @Override
    public MetaPermission permission() {
        return new MysqlMetaUser(getJdbcDataSource());
    }

    @Override
    public MetaSearch search() {
        throw new UnsupportedOperationException("MySQL 暂不支持搜索引擎元数据");
    }

    @Override
    public MetaSearch search(String indexName) {
        throw new UnsupportedOperationException("MySQL 暂不支持搜索引擎元数据");
    }

    private DataSource getJdbcDataSource() {
        try {
            return getJdbcConnection().getConnection();
        } catch (Exception e) {
            throw new IllegalStateException("无法获取 JDBC 数据源", e);
        }
    }

    protected Connection getJdbcConnection() throws Exception {
        com.chua.common.support.lang.datasource.engine.EngineDataSource<?> eds =
                engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) throw new IllegalStateException("默认数据源未配置");
        Object source = eds.getSource();
        if (source instanceof DataSource ds) return ds.getConnection();
        throw new IllegalStateException("数据源类型不支持 JDBC: " + source.getClass().getName());
    }
}
