package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.*;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.meta.JdbcMetaData;

/**
* PostgreSQL 元数据入口。
* <p>
* 继承自 {@link JdbcMetaData}，复用方言提供的触发器/存储过程查询 SQL。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("postgresql")
public class PostgresqlMetaData extends JdbcMetaData {

    /**
    * postgresqlmeta数据。
    * @param engine engine
    */
    public PostgresqlMetaData(Engine engine) {
        super(engine);
    }

    @Override
    public MetaTable table() {
        throw new UnsupportedOperationException("PostgreSQL 表元数据暂不支持");
    }

    @Override
    public MetaTable table(String tableName) {
        throw new UnsupportedOperationException("PostgreSQL 表元数据暂不支持");
    }

    @Override
    public MetaView view() {
        throw new UnsupportedOperationException("PostgreSQL 视图元数据暂不支持");
    }

    @Override
    public MetaView view(String viewName) {
        throw new UnsupportedOperationException("PostgreSQL 视图元数据暂不支持");
    }

    @Override
    public MetaIndex index() {
        throw new UnsupportedOperationException("PostgreSQL 索引元数据暂不支持");
    }

    @Override
    public MetaIndex index(String indexName) {
        throw new UnsupportedOperationException("PostgreSQL 索引元数据暂不支持");
    }

    @Override
    public MetaTrigger trigger() {
        throw new UnsupportedOperationException("PostgreSQL 触发器元数据暂不支持");
    }

    @Override
    public MetaTrigger trigger(String triggerName) {
        throw new UnsupportedOperationException("PostgreSQL 触发器元数据暂不支持");
    }

    @Override
    public MetaProcedure procedure() {
        throw new UnsupportedOperationException("PostgreSQL 存储过程元数据暂不支持");
    }

    @Override
    public MetaProcedure procedure(String procedureName) {
        throw new UnsupportedOperationException("PostgreSQL 存储过程元数据暂不支持");
    }

    @Override
    public MetaForeignKey fk() {
        throw new UnsupportedOperationException("PostgreSQL 外键元数据暂不支持");
    }

    @Override
    public MetaForeignKey fk(String fkName) {
        throw new UnsupportedOperationException("PostgreSQL 外键元数据暂不支持");
    }

    @Override
    public MetaUser user() {
        throw new UnsupportedOperationException("PostgreSQL 用户管理暂不支持");
    }

    @Override
    public MetaPermission permission() {
        throw new UnsupportedOperationException("PostgreSQL 权限管理暂不支持");
    }

    @Override
    public MetaSearch search() {
        throw new UnsupportedOperationException("PostgreSQL 搜索引擎元数据暂不支持");
    }

    @Override
    public MetaSearch search(String indexName) {
        throw new UnsupportedOperationException("PostgreSQL 搜索引擎元数据暂不支持");
    }
}
