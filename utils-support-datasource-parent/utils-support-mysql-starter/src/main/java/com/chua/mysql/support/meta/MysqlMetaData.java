package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.meta.AbstractMetaData;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MySQL 元数据入口。
 * <p>
 * 通过 SPI 机制注册为 MySQL 引擎的元数据实现。
 * 提供表、视图、索引、触发器、存储过程、外键的元数据操作能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("mysql")
public class MysqlMetaData extends AbstractMetaData {

    /**
     * 构造方法。
     *
     * @param engine 引擎实例
     */
    public MysqlMetaData(Engine engine) {
        super(engine);
    }

    @Override
    /** Table */
    public MetaTable table() {
        return new MysqlMetaTable(this, engine);
    }

    @Override
    /** Table */
    public MetaTable table(String tableName) {
        return new MysqlMetaTable(this, engine, tableName);
    }

    @Override
    /** View */
    public MetaView view() {
        return new MysqlMetaView(this, engine);
    }

    @Override
    /** View */
    public MetaView view(String viewName) {
        return new MysqlMetaView(this, engine, viewName);
    }

    @Override
    /** Index */
    public MetaIndex index() {
        return new MysqlMetaIndex(this, engine);
    }

    @Override
    /** Index */
    public MetaIndex index(String indexName) {
        return new MysqlMetaIndex(this, engine, indexName);
    }

    @Override
    /** Trigger */
    public MetaTrigger trigger() {
        return new MysqlMetaTrigger(this, engine);
    }

    @Override
    /** Trigger */
    public MetaTrigger trigger(String triggerName) {
        return new MysqlMetaTrigger(this, engine, triggerName);
    }

    @Override
    /** Procedure */
    public MetaProcedure procedure() {
        return new MysqlMetaProcedure(this, engine);
    }

    @Override
    /** Procedure */
    public MetaProcedure procedure(String procedureName) {
        return new MysqlMetaProcedure(this, engine, procedureName);
    }

    @Override
    /** Fk */
    public MetaForeignKey fk() {
        return new MysqlMetaForeignKey(this, engine);
    }

    @Override
    /** Fk */
    public MetaForeignKey fk(String fkName) {
        return new MysqlMetaForeignKey(this, engine, fkName);
    }

    @Override
    /** 搜索 */
    public MetaSearch search() {
        throw new UnsupportedOperationException("MySQL 暂不支持搜索引擎元数据");
    }

    @Override
    /** 搜索 */
    public MetaSearch search(String indexName) {
        throw new UnsupportedOperationException("MySQL 暂不支持搜索引擎元数据");
    }
}
