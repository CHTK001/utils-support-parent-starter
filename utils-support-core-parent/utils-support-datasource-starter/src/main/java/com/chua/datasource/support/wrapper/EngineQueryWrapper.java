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
* @since 4.0.0.42
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

    /**
    * 构造函数。
    *
    * @param engine      引擎实例
    * @param entityClass 实体类类型
     */
    public EngineQueryWrapper(AbstractEngine engine, Class<T> entityClass) {
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
    protected LambdaQueryWrapper<T> newInstance() {
        return new EngineQueryWrapper<>(engine, entityClass);
    }

    @Override
    /** Eq */
    public EngineQueryWrapper<T> eq(SFunction<T, ?> column, Object value) {
        super.eq(column, value);
        return this;
    }

    @Override
    /** Ne */
    public EngineQueryWrapper<T> ne(SFunction<T, ?> column, Object value) {
        super.ne(column, value);
        return this;
    }

    @Override
    /** Gt */
    public EngineQueryWrapper<T> gt(SFunction<T, ?> column, Object value) {
        super.gt(column, value);
        return this;
    }

    @Override
    /** Ge */
    public EngineQueryWrapper<T> ge(SFunction<T, ?> column, Object value) {
        super.ge(column, value);
        return this;
    }

    @Override
    /** Lt */
    public EngineQueryWrapper<T> lt(SFunction<T, ?> column, Object value) {
        super.lt(column, value);
        return this;
    }

    @Override
    /** Le */
    public EngineQueryWrapper<T> le(SFunction<T, ?> column, Object value) {
        super.le(column, value);
        return this;
    }

    @Override
    /** Like */
    public EngineQueryWrapper<T> like(SFunction<T, ?> column, Object value) {
        super.like(column, value);
        return this;
    }

    @Override
    /** likeleft */
    public EngineQueryWrapper<T> likeLeft(SFunction<T, ?> column, Object value) {
        super.likeLeft(column, value);
        return this;
    }

    @Override
    /** likeright */
    public EngineQueryWrapper<T> likeRight(SFunction<T, ?> column, Object value) {
        super.likeRight(column, value);
        return this;
    }

    @Override
    /** 入 */
    public EngineQueryWrapper<T> in(SFunction<T, ?> column, Collection<?> values) {
        super.in(column, values);
        return this;
    }

    @Override
    /** not入 */
    public EngineQueryWrapper<T> notIn(SFunction<T, ?> column, Collection<?> values) {
        super.notIn(column, values);
        return this;
    }

    @Override
    /** 是否空 */
    public EngineQueryWrapper<T> isNull(SFunction<T, ?> column) {
        super.isNull(column);
        return this;
    }

    @Override
    /** 是否not空 */
    public EngineQueryWrapper<T> isNotNull(SFunction<T, ?> column) {
        super.isNotNull(column);
        return this;
    }

    @Override
    /** Between */
    public EngineQueryWrapper<T> between(SFunction<T, ?> column, Object start, Object end) {
        super.between(column, start, end);
        return this;
    }

    @Override
    /** 订单byasc */
    public EngineQueryWrapper<T> orderByAsc(SFunction<T, ?> column) {
        super.orderByAsc(column);
        return this;
    }

    @Override
    /** 订单bydesc */
    public EngineQueryWrapper<T> orderByDesc(SFunction<T, ?> column) {
        super.orderByDesc(column);
        return this;
    }

    @Override
    /** table别名 */
    public EngineQueryWrapper<T> tableAlias(String alias) {
        super.tableAlias(alias);
        return this;
    }

    @Override
    /** 列表 */
    public List<T> list() {
        return engine.executeQuery(this, entityClass);
    }

    @Override
    /** One */
    public T one() {
        List<T> list = list();
        if (list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    @Override
    /** Page */
    public Page<T> page(int pn, int ps) {
        return engine.executePage(this, entityClass, pn, ps);
    }
}