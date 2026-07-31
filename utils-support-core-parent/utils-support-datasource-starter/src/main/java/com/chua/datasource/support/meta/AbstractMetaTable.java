package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.TableAlterBuilder;
import com.chua.common.support.lang.datasource.meta.TableCreateBuilder;
import com.chua.common.support.lang.datasource.table.TableDef;

import java.util.List;

/**
 * 表元数据操作抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用，提供表名上下文。
 * 子类只需实现具体的 JDBC 元数据读取和 DDL 生成逻辑。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractMetaTable implements MetaTable {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 当前表名
     */
    protected String tableName;

    /**
     * 构造方法（无表名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaTable(AbstractMetaData metaData, Engine engine) {
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
    protected AbstractMetaTable(AbstractMetaData metaData, Engine engine, String tableName) {
        this.metaData = metaData;
        this.engine = engine;
        this.tableName = tableName;
    }

    @Override
    public MetaTable catalog(String catalog) {
        this.metaData.catalog = catalog;
        return this;
    }

    @Override
    public MetaTable schema(String schema) {
        this.metaData.schema = schema;
        return this;
    }

    @Override
    public List<TableDef> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    public TableDef get() {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    public TableCreateBuilder create(String tableName) {
        throw new UnsupportedOperationException("请实现 create() 方法");
    }

    @Override
    public TableAlterBuilder alter() {
        throw new UnsupportedOperationException("请实现 alter() 方法");
    }

    @Override
    public boolean drop() {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }

    @Override
    public boolean rename(String newName) {
        throw new UnsupportedOperationException("请实现 rename() 方法");
    }
}
