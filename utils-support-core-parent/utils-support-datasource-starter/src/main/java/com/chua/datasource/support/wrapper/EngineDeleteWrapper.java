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
 * @since 4.0.0.42
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

    /**
     * 构造函数。
     *
     * @param engine      引擎实例
     * @param entityClass 实体类类型
     */
    public EngineDeleteWrapper(AbstractEngine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
        this.entityClass = entityClass;
    }

    @Override
    /** 解析Column */
    protected String resolveColumn(SFunction<T, ?> col) {
        return LambdaUtils.resolveObject(col);
    }

    @Override
    /** 新instance */
    protected LambdaDeleteWrapper<T> newInstance() {
        return new EngineDeleteWrapper<>(engine, entityClass);
    }

    @Override
    /** 移除 */
    public int remove() {
        return engine.executeDelete(this.buildSql());
    }
}