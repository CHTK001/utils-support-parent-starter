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
 * @since 2024/12/12
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

    public EngineUpdateWrapper(AbstractEngine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
        this.entityClass = entityClass;
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> col) {
        return LambdaUtils.resolveObject(col);
    }

    @Override
    protected LambdaUpdateWrapper<T> newInstance() {
        return new EngineUpdateWrapper<>(engine, entityClass);
    }

    @Override
    public int update() {
        return engine.executeUpdate(this.buildSql());
    }
}