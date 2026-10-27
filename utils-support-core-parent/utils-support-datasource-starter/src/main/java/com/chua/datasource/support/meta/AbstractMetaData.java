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
 * 元数据操作入口抽象基类。
 * <p>
 * 持有 Engine 引用，为每个元数据子接口提供默认的实例化逻辑。
 * 子类必须实现所有抽象方法来提供具体的元数据操作能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractMetaData implements MetaData {

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 当前 catalog
     */
    protected String catalog;

    /**
     * 当前 模式
     */
    protected String schema;

    /**
     * 构造方法。
     *
     * @param engine 引擎实例
     */
    protected AbstractMetaData(Engine engine) {
        this.engine = engine;
    }

    /**
     * Table
    */
    @Override
    public abstract MetaTable table();

    /**
     * Table
    */
    @Override
    public abstract MetaTable table(String tableName);

    /**
     * View
    */
    @Override
    public abstract MetaView view();

    /**
     * View
    */
    @Override
    public abstract MetaView view(String viewName);

    /**
     * 索引
    */
    @Override
    public abstract MetaIndex index();

    /**
     * 索引
    */
    @Override
    public abstract MetaIndex index(String indexName);

    /**
     * Trigger
    */
    @Override
    public abstract MetaTrigger trigger();

    /**
     * Trigger
    */
    @Override
    public abstract MetaTrigger trigger(String triggerName);

    /**
     * Procedure
    */
    @Override
    public abstract MetaProcedure procedure();

    /**
     * Procedure
    */
    @Override
    public abstract MetaProcedure procedure(String procedureName);

    /**
     * Fk
    */
    @Override
    public abstract MetaForeignKey fk();

    /**
     * Fk
    */
    @Override
    public abstract MetaForeignKey fk(String fkName);

    /**
     * 用户
    */
    @Override
    public abstract MetaUser user();

    /**
     * 权限
    */
    @Override
    public abstract MetaPermission permission();

    /**
     * 搜索
    */
    @Override
    public abstract MetaSearch search();

    /**
     * 搜索
    */
    @Override
    public abstract MetaSearch search(String indexName);

    /**
     * 获取当前 catalog。
     *
     * @return catalog 名称
     */
    public String getCatalog() {
        return catalog;
    }

    /**
     * 获取当前 模式。
     *
     * @return schema 名称
     */
    public String getSchema() {
        return schema;
    }

    /**
     * 获取引擎实例。
     *
     * @return 引擎实例
     */
    public Engine getEngine() {
        return engine;
    }
}
