package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.*;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.meta.JdbcMetaData;

/**
 * MySQL 元数据入口。
 * <p>
 * 继承自 {@link JdbcMetaData}，复用方言提供的触发器/存储过程查询 SQL。
 * 提供表、视图、索引、用户、权限的元数据操作能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("mysql")
public class MysqlMetaData extends JdbcMetaData {

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
        return new MysqlMetaUser(getDataSource());
    }

    @Override
    public MetaPermission permission() {
        return new MysqlMetaPermission(getDataSource());
    }

    @Override
    public MetaSearch search() {
        throw new UnsupportedOperationException("MySQL 暂不支持搜索引擎元数据");
    }

    @Override
    public MetaSearch search(String indexName) {
        throw new UnsupportedOperationException("MySQL 暂不支持搜索引擎元数据");
    }
}
