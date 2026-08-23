package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.IndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.MetaIndex;

import java.util.List;

/**
 * 索引入口抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用，提供表名上下文。
 * 子类只需实现具体的 JDBC 元数据读取和 DDL 生成逻辑。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractMetaIndex implements MetaIndex {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 当前索引名
     */
    protected String indexName;

    /**
     * 所属表名
     */
    protected String tableName;

    /**
     * 构造方法（无索引名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaIndex(AbstractMetaData metaData, Engine engine) {
        this.metaData = metaData;
        this.engine = engine;
    }

    /**
     * 构造方法（带索引名上下文）。
     *
     * @param metaData  元数据入口
     * @param engine    引擎实例
     * @param indexName 索引名
     */
    protected AbstractMetaIndex(AbstractMetaData metaData, Engine engine, String indexName) {
        this.metaData = metaData;
        this.engine = engine;
        this.indexName = indexName;
    }

    @Override
    /** OnTable */
    public MetaIndex onTable(String tableName) {
        this.tableName = tableName;
        return this;
    }

    @Override
    /** List */
    public List<IndexMetadata> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    /** 获取 */
    public IndexMetadata get(String indexName) {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    /** 创建 */
    public IndexCreateBuilder create(String indexName) {
        throw new UnsupportedOperationException("请实现 create() 方法");
    }

    @Override
    /** Drop */
    public boolean drop(String indexName) {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }
}
