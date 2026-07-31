package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;

/**
 * Engine 删除包装器。
 *
 * @param <T> 实体类型
 * @author CH
 * @since 2024/12/12
 */
public class EngineDeleteWrapper<T> extends LambdaDeleteWrapper<T> {

    /**
     * 引擎实例。
     */
    private final AbstractEngine engine;

    /**
     * 实体类类型。
     */
    private final Class<T> entityClass;

    public EngineDeleteWrapper(AbstractEngine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
        this.entityClass = entityClass;
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> col) {
        return LambdaUtils.resolveObject(col);
    }

    @Override
    protected LambdaDeleteWrapper<T> newInstance() {
        return new EngineDeleteWrapper<>(engine, entityClass);
    }

    @Override
    public int remove() {
        return engine.executeDelete(this.buildSql());
    }
}