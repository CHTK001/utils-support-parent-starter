package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.ForeignKeyCreateBuilder;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;

import java.util.List;

/**
 * 外键元数据操作抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用，提供表名上下文。
 * 子类只需实现具体的 JDBC 元数据读取和 DDL 生成逻辑。
 * </p>
 *
 * @since 4.0.0.42
 */
public abstract class AbstractMetaForeignKey implements MetaForeignKey {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 所属表名
     */
    protected String tableName;

    /**
     * 构造方法（无表名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaForeignKey(AbstractMetaData metaData, Engine engine) {
        this.metaData = metaData;
        this.engine = engine;
    }

    /**
     * 构造方法（带表名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     * @param tableName 表名
     */
    protected AbstractMetaForeignKey(AbstractMetaData metaData, Engine engine, String tableName) {
        this.metaData = metaData;
        this.engine = engine;
        this.tableName = tableName;
    }

    @Override
    /** OnTable */
    public MetaForeignKey onTable(String tableName) {
        this.tableName = tableName;
        return this;
    }

    @Override
    /** List */
    public List<ForeignKeyDef> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    /** 获取 */
    public ForeignKeyDef get(String fkName) {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    /** 添加 */
    public ForeignKeyCreateBuilder add(String fkName) {
        throw new UnsupportedOperationException("请实现 add() 方法");
    }

    @Override
    /** Drop */
    public boolean drop(String fkName) {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }
}
