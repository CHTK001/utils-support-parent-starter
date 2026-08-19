package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.TriggerCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.TriggerDef;

import java.util.List;

/**
 * 触发器元数据操作抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用，提供触发器名和表名上下文。
 * 子类只需实现具体的 JDBC 元数据读取和 DDL 生成逻辑。
 * </p>
 *
 * @since 4.0.0.42
 */
public abstract class AbstractMetaTrigger implements MetaTrigger {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 当前触发器名
     */
    protected String triggerName;

    /**
     * 所属表名
     */
    protected String tableName;

    /**
     * 构造方法（无触发器名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaTrigger(AbstractMetaData metaData, Engine engine) {
        this.metaData = metaData;
        this.engine = engine;
    }

    /**
     * 构造方法（带触发器名上下文）。
     *
     * @param metaData   元数据入口
     * @param engine     引擎实例
     * @param triggerName 触发器名
     */
    protected AbstractMetaTrigger(AbstractMetaData metaData, Engine engine, String triggerName) {
        this.metaData = metaData;
        this.engine = engine;
        this.triggerName = triggerName;
    }

    @Override
    /** OnTable */
    public MetaTrigger onTable(String tableName) {
        this.tableName = tableName;
        return this;
    }

    @Override
    /** List */
    public List<TriggerDef> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    /** 获取 */
    public TriggerDef get(String triggerName) {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    /** 创建 */
    public TriggerCreateBuilder create(String triggerName) {
        throw new UnsupportedOperationException("请实现 create() 方法");
    }

    @Override
    /** Drop */
    public boolean drop(String triggerName) {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }

    @Override
    /** 启用 */
    public boolean enable(String triggerName) {
        throw new UnsupportedOperationException("请实现 enable() 方法");
    }

    @Override
    /** 禁用 */
    public boolean disable(String triggerName) {
        throw new UnsupportedOperationException("请实现 disable() 方法");
    }

    @Override
    /** 解析Dialect */
    public Dialect resolveDialect() {
        return engine.getDialect(metaData.getEngine().getDefaultDataSourceName());
    }
}
