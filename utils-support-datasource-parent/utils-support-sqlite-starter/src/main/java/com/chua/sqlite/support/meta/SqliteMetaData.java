package com.chua.sqlite.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.JdbcMetaTable;

/**
 * SQLite 元数据入口，提供对象化 DDL（建表/删表/重命名）。
 *
 * <p>通过 SPI 注册，SqliteEngine.meta() 自动加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SqliteMetaData extends AbstractMetaData {

    /**
     * 构造方法。
     *
     * @param engine 引擎实例
     */
    public SqliteMetaData(Engine engine) {
        super(engine);
    }

    @Override
    public MetaTable table() {
        return new JdbcMetaTable(this, engine);
    }

    @Override
    public MetaTable table(String tableName) {
        return new JdbcMetaTable(this, engine, tableName);
    }

    @Override
    public MetaView view() {
        throw new UnsupportedOperationException("SQLite 暂不支持视图元数据操作");
    }

    @Override
    public MetaView view(String viewName) {
        throw new UnsupportedOperationException("SQLite 暂不支持视图元数据操作");
    }

    @Override
    public MetaIndex index() {
        throw new UnsupportedOperationException("SQLite 暂不支持索引入口操作");
    }

    @Override
    public MetaIndex index(String indexName) {
        throw new UnsupportedOperationException("SQLite 暂不支持索引入口操作");
    }

    @Override
    public MetaTrigger trigger() {
        throw new UnsupportedOperationException("SQLite 暂不支持触发器元数据操作");
    }

    @Override
    public MetaTrigger trigger(String triggerName) {
        throw new UnsupportedOperationException("SQLite 暂不支持触发器元数据操作");
    }

    @Override
    public MetaProcedure procedure() {
        throw new UnsupportedOperationException("SQLite 暂不支持存储过程元数据操作");
    }

    @Override
    public MetaProcedure procedure(String procedureName) {
        throw new UnsupportedOperationException("SQLite 暂不支持存储过程元数据操作");
    }

    @Override
    public MetaForeignKey fk() {
        throw new UnsupportedOperationException("SQLite 暂不支持外键元数据操作");
    }

    @Override
    public MetaForeignKey fk(String fkName) {
        throw new UnsupportedOperationException("SQLite 暂不支持外键元数据操作");
    }

    @Override
    public MetaSearch search() {
        throw new UnsupportedOperationException("SQLite 暂不支持搜索引擎索引操作");
    }

    @Override
    public MetaSearch search(String indexName) {
        throw new UnsupportedOperationException("SQLite 暂不支持搜索引擎索引操作");
    }
}