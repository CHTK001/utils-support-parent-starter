package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import java.util.List;
import java.util.Map;

/**
 * 搜索引擎元数据操作抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用。
 * 子类只需实现具体的搜索引擎客户端交互逻辑。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractMetaSearch implements MetaSearch {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 构造方法。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaSearch(AbstractMetaData metaData, Engine engine) {
        this.metaData = metaData;
        this.engine = engine;
    }

    @Override
    /**
     * 列表
    */
    public List<SearchIndexDef> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    /**
     * 获取
    */
    public SearchIndexDef get(String indexName) {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    /**
     * 创建
    */
    public SearchIndexCreateBuilder create(String indexName) {
        throw new UnsupportedOperationException("请实现 create() 方法");
    }

    @Override
    /**
     * 掉落
    */
    public boolean drop(String indexName) {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }

    @Override
    /**
     * Refresh
    */
    public boolean refresh(String indexName) {
        throw new UnsupportedOperationException("请实现 refresh() 方法");
    }

    @Override
    /**
     * 优化
    */
    public boolean optimize(String indexName) {
        throw new UnsupportedOperationException("请实现 optimize() 方法");
    }
}
