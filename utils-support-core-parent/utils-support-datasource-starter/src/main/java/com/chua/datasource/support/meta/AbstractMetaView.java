package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.common.support.lang.datasource.meta.ViewAlterBuilder;
import com.chua.common.support.lang.datasource.meta.ViewCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ViewDef;

import java.util.List;

/**
 * 视图元数据操作抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用，提供视图名上下文。
 * 子类只需实现具体的 JDBC 元数据读取和 DDL 生成逻辑。
 * </p>
 *
 * @since 4.0.0.42
 */
public abstract class AbstractMetaView implements MetaView {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 当前视图名
     */
    protected String viewName;

    /**
     * 构造方法（无视图名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaView(AbstractMetaData metaData, Engine engine) {
        this.metaData = metaData;
        this.engine = engine;
    }

    /**
     * 构造方法（带视图名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     * @param viewName 视图名
     */
    protected AbstractMetaView(AbstractMetaData metaData, Engine engine, String viewName) {
        this.metaData = metaData;
        this.engine = engine;
        this.viewName = viewName;
    }

    @Override
    public List<ViewDef> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    public ViewDef get() {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    public ViewCreateBuilder create(String viewName) {
        throw new UnsupportedOperationException("请实现 create() 方法");
    }

    @Override
    public ViewAlterBuilder alter() {
        throw new UnsupportedOperationException("请实现 alter() 方法");
    }

    @Override
    public boolean drop() {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }
}
