package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Engine 查询包装器。
 *
 * @param <T> 实体类型
 * @author CH
 * @since 2024/12/12
 */
public class EngineQueryWrapper<T> extends LambdaQueryWrapper<T> {

    /**
     * 引擎实例。
     */
    private final AbstractEngine engine;

    /**
     * 实体类类型。
     */
    private final Class<T> entityClass;

    public EngineQueryWrapper(AbstractEngine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
        this.entityClass = entityClass;
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> col) {
        return LambdaUtils.resolveObject(col);
    }

    @Override
    protected LambdaQueryWrapper<T> newInstance() {
        return new EngineQueryWrapper<>(engine, entityClass);
    }

    @Override
    public EngineQueryWrapper<T> eq(SFunction<T, ?> column, Object value) {
        super.eq(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> ne(SFunction<T, ?> column, Object value) {
        super.ne(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> gt(SFunction<T, ?> column, Object value) {
        super.gt(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> ge(SFunction<T, ?> column, Object value) {
        super.ge(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> lt(SFunction<T, ?> column, Object value) {
        super.lt(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> le(SFunction<T, ?> column, Object value) {
        super.le(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> like(SFunction<T, ?> column, Object value) {
        super.like(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> likeLeft(SFunction<T, ?> column, Object value) {
        super.likeLeft(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> likeRight(SFunction<T, ?> column, Object value) {
        super.likeRight(column, value);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> in(SFunction<T, ?> column, Collection<?> values) {
        super.in(column, values);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> notIn(SFunction<T, ?> column, Collection<?> values) {
        super.notIn(column, values);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> isNull(SFunction<T, ?> column) {
        super.isNull(column);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> isNotNull(SFunction<T, ?> column) {
        super.isNotNull(column);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> between(SFunction<T, ?> column, Object start, Object end) {
        super.between(column, start, end);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> orderByAsc(SFunction<T, ?> column) {
        super.orderByAsc(column);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> orderByDesc(SFunction<T, ?> column) {
        super.orderByDesc(column);
        return this;
    }

    @Override
    public EngineQueryWrapper<T> tableAlias(String alias) {
        super.tableAlias(alias);
        return this;
    }

    @Override
    public List<T> list() {
        return engine.executeQuery(this, entityClass);
    }

    @Override
    public T one() {
        List<T> list = list();
        if (list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    @Override
    public Page<T> page(int pn, int ps) {
        return engine.executePage(this, entityClass, pn, ps);
    }
}