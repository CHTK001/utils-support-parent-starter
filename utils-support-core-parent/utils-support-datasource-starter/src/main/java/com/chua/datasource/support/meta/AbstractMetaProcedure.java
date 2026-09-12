package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.ProcedureCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ProcedureDef;

import java.util.List;
import java.util.Map;

/**
 * 存储过程元数据操作抽象基类。
 * <p>
 * 持有 {@link AbstractMetaData} 引用，提供存储过程名上下文。
 * 子类只需实现具体的 JDBC 元数据读取和 DDL 生成逻辑。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractMetaProcedure implements MetaProcedure {

    /**
     * 元数据入口
     */
    protected final AbstractMetaData metaData;

    /**
     * 引擎实例
     */
    protected final Engine engine;

    /**
     * 当前存储过程名
     */
    protected String procedureName;

    /**
     * 构造方法（无存储过程名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected AbstractMetaProcedure(AbstractMetaData metaData, Engine engine) {
        this.metaData = metaData;
        this.engine = engine;
    }

    /**
     * 构造方法（带存储过程名上下文）。
     *
     * @param metaData     元数据入口
     * @param engine       引擎实例
     * @param procedureName 存储过程名
     */
    protected AbstractMetaProcedure(AbstractMetaData metaData, Engine engine, String procedureName) {
        this.metaData = metaData;
        this.engine = engine;
        this.procedureName = procedureName;
    }

    @Override
    /** 列表 */
    public List<ProcedureDef> list() {
        throw new UnsupportedOperationException("请实现 list() 方法");
    }

    @Override
    /** 获取 */
    public ProcedureDef get(String procedureName) {
        throw new UnsupportedOperationException("请实现 get() 方法");
    }

    @Override
    /** 创建 */
    public ProcedureCreateBuilder create(String procedureName) {
        throw new UnsupportedOperationException("请实现 create() 方法");
    }

    @Override
    /** 掉落 */
    public boolean drop(String procedureName) {
        throw new UnsupportedOperationException("请实现 drop() 方法");
    }

    @Override
    /** 调用 */
    public List<Map<String, Object>> call(Object... args) {
        throw new UnsupportedOperationException("请实现 call() 方法");
    }

    @Override
    /** 调用 */
    public List<Map<String, Object>> call(String procedureName, Object... args) {
        throw new UnsupportedOperationException("请实现 call() 方法");
    }
}
