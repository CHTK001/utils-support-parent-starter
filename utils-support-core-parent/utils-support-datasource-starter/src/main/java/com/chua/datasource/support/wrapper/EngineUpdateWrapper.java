package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;

/**
 * Engine 更新包装器。
 *
 * @param <T> 实体类型
 * @author CH
 * @since 4.0.0.42
 */
public class EngineUpdateWrapper<T> extends LambdaUpdateWrapper<T> {

    /**
     * 引擎实例。
     */
    private final AbstractEngine engine;

    /**
     * 实体类类型。
     */
    private final Class<T> entityClass;

    /**
     * 构造函数。
     *
     * @param engine      引擎实例
     * @param entityClass 实体类类型
     */
    public EngineUpdateWrapper(AbstractEngine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
        this.entityClass = entityClass;
    }

    @Override
    /**
     * 解析Column
    */
    protected String resolveColumn(SFunction<T, ?> col) {
        return LambdaUtils.resolveColumn(col);
    }

    @Override
    /**
     * 新instance
    */
    protected LambdaUpdateWrapper<T> newInstance() {
        return new EngineUpdateWrapper<>(engine, entityClass);
    }

    @Override
    /**
     * 更新
    */
    public int update() {
        return engine.executeUpdate(this.buildSql());
    }
}
