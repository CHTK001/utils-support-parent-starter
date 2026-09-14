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
        return LambdaUtils.resolveColumn(col);
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
    /** 限制返回行数 */
    public EngineQueryWrapper<T> limit(int limit) {
        super.limit(limit);
        return this;
    }

    @Override
    /** 设置偏移行数 */
    public EngineQueryWrapper<T> offset(int offset) {
        super.offset(offset);
        return this;
    }

    @Override
    /** Lambda 投影列 */
    public EngineQueryWrapper<T> select(SFunction<T, ?> column) {
        super.select(column);
        return this;
    }

    @Override
    /** 字符串投影列 */
    public EngineQueryWrapper<T> select(String... columns) {
        super.select(columns);
        return this;
    }

    @Override
    /** 聚合函数投影列 */
    public EngineQueryWrapper<T> selectFunc(String function, String column, String alias) {
        super.selectFunc(function, column, alias);
        return this;
    }

    @Override
    /** COUNT(*) 聚合投影列 */
    public EngineQueryWrapper<T> selectCount(String alias) {
        super.selectCount(alias);
        return this;
    }

    @Override
    /** SUM 聚合投影列 */
    public EngineQueryWrapper<T> selectSum(String column, String alias) {
        super.selectSum(column, alias);
        return this;
    }

    @Override
    /** AVG 聚合投影列 */
    public EngineQueryWrapper<T> selectAvg(String column, String alias) {
        super.selectAvg(column, alias);
        return this;
    }

    @Override
    /** MAX 聚合投影列 */
    public EngineQueryWrapper<T> selectMax(String column, String alias) {
        super.selectMax(column, alias);
        return this;
    }

    @Override
    /** MIN 聚合投影列 */
    public EngineQueryWrapper<T> selectMin(String column, String alias) {
        super.selectMin(column, alias);
        return this;
    }

    @Override
    /** 单列分组 */
    public EngineQueryWrapper<T> groupBy(SFunction<T, ?> column) {
        super.groupBy(column);
        return this;
    }

    @Override
    @SafeVarargs
    public final EngineQueryWrapper<T> groupBy(String... columns) {
        super.groupBy(columns);
        return this;
    }

    @Override
    /** HAVING 分组过滤条件 */
    public EngineQueryWrapper<T> having(String condition, Object... params) {
        super.having(condition, params);
        return this;
    }

    @Override
    /** INNER JOIN 关联 */
    public EngineQueryWrapper<T> innerJoin(String table, String onCondition) {
        super.innerJoin(table, onCondition);
        return this;
    }

    @Override
    /** INNER JOIN 关联（显式别名） */
    public EngineQueryWrapper<T> innerJoin(String table, String alias, String onCondition) {
        super.innerJoin(table, alias, onCondition);
        return this;
    }

    @Override
    /** LEFT JOIN 关联 */
    public EngineQueryWrapper<T> leftJoin(String table, String onCondition) {
        super.leftJoin(table, onCondition);
        return this;
    }

    @Override
    /** LEFT JOIN 关联（显式别名） */
    public EngineQueryWrapper<T> leftJoin(String table, String alias, String onCondition) {
        super.leftJoin(table, alias, onCondition);
        return this;
    }

    @Override
    /** RIGHT JOIN 关联 */
    public EngineQueryWrapper<T> rightJoin(String table, String onCondition) {
        super.rightJoin(table, onCondition);
        return this;
    }

    @Override
    /** RIGHT JOIN 关联（显式别名） */
    public EngineQueryWrapper<T> rightJoin(String table, String alias, String onCondition) {
        super.rightJoin(table, alias, onCondition);
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

    @Override
    /** 统计当前条件命中的总行数 */
    public long count() {
        return engine.queryCount(this);
    }
}