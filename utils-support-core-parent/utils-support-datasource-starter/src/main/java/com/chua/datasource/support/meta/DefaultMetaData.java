package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaPermission;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaUser;
import com.chua.common.support.lang.datasource.meta.MetaView;

/**
* 默认元数据实现，所有操作抛出 unsupportedoperation异常。
* <p>
* 作为 {@link AbstractEngine#meta()} 的默认返回，非 SQL 引擎可使用此实现。
* SQL 引擎应返回数据库特定的 {@link AbstractMetaData} 子类（如 {@link MysqlMetaData}）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultMetaData extends AbstractMetaData {

    /**
    * 构造方法。
    *
    * @param engine 引擎实例
     */
    public DefaultMetaData(Engine engine) {
        super(engine);
    }

    @Override
    /** Table */
    public MetaTable table() {
        throw new UnsupportedOperationException("当前引擎不支持表元数据操作");
    }

    @Override
    /** Table */
    public MetaTable table(String tableName) {
        throw new UnsupportedOperationException("当前引擎不支持表元数据操作");
    }

    @Override
    /** View */
    public MetaView view() {
        throw new UnsupportedOperationException("当前引擎不支持视图元数据操作");
    }

    @Override
    /** View */
    public MetaView view(String viewName) {
        throw new UnsupportedOperationException("当前引擎不支持视图元数据操作");
    }

    @Override
    /** 索引 */
    public MetaIndex index() {
        throw new UnsupportedOperationException("当前引擎不支持索引入口操作");
    }

    @Override
    /** 索引 */
    public MetaIndex index(String indexName) {
        throw new UnsupportedOperationException("当前引擎不支持索引入口操作");
    }

    @Override
    /** Trigger */
    public MetaTrigger trigger() {
        throw new UnsupportedOperationException("当前引擎不支持触发器元数据操作");
    }

    @Override
    /** Trigger */
    public MetaTrigger trigger(String triggerName) {
        throw new UnsupportedOperationException("当前引擎不支持触发器元数据操作");
    }

    @Override
    /** Procedure */
    public MetaProcedure procedure() {
        throw new UnsupportedOperationException("当前引擎不支持存储过程元数据操作");
    }

    @Override
    /** Procedure */
    public MetaProcedure procedure(String procedureName) {
        throw new UnsupportedOperationException("当前引擎不支持存储过程元数据操作");
    }

    @Override
    /** Fk */
    public MetaForeignKey fk() {
        throw new UnsupportedOperationException("当前引擎不支持外键元数据操作");
    }

    @Override
    /** Fk */
    public MetaForeignKey fk(String fkName) {
        throw new UnsupportedOperationException("当前引擎不支持外键元数据操作");
    }

    @Override
    /** 用户 */
    public MetaUser user() {
        throw new UnsupportedOperationException("当前引擎不支持用户元数据操作");
    }

    @Override
    /** 权限 */
    public MetaPermission permission() {
        throw new UnsupportedOperationException("当前引擎不支持权限元数据操作");
    }

    @Override
    /** 搜索 */
    public MetaSearch search() {
        throw new UnsupportedOperationException("当前引擎不支持搜索引擎索引操作");
    }

    @Override
    /** 搜索 */
    public MetaSearch search(String indexName) {
        throw new UnsupportedOperationException("当前引擎不支持搜索引擎索引操作");
    }
}
