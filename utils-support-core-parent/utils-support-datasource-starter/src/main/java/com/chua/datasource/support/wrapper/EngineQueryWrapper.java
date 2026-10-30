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

    /**
     * 解析Column
    */
    @Override
    protected String resolveColumn(SFunction<T, ?> col) {
        return LambdaUtils.resolveColumn(col);
    }

    /**
     * 新instance
    */
    @Override
    protected LambdaQueryWrapper<T> newInstance() {
        return new EngineQueryWrapper<>(engine, entityClass);
    }

    /**
     * Eq
    */
    @Override
    public EngineQueryWrapper<T> eq(SFunction<T, ?> column, Object value) {
        super.eq(column, value);
        return this;
    }

    /**
     * Ne
    */
    @Override
    public EngineQueryWrapper<T> ne(SFunction<T, ?> column, Object value) {
        super.ne(column, value);
        return this;
    }

    /**
     * Gt
    */
    @Override
    public EngineQueryWrapper<T> gt(SFunction<T, ?> column, Object value) {
        super.gt(column, value);
        return this;
    }

    /**
     * Ge
    */
    @Override
    public EngineQueryWrapper<T> ge(SFunction<T, ?> column, Object value) {
        super.ge(column, value);
        return this;
    }

    /**
     * Lt
    */
    @Override
    public EngineQueryWrapper<T> lt(SFunction<T, ?> column, Object value) {
        super.lt(column, value);
        return this;
    }

    /**
     * Le
    */
    @Override
    public EngineQueryWrapper<T> le(SFunction<T, ?> column, Object value) {
        super.le(column, value);
        return this;
    }

    /**
     * Like
    */
    @Override
    public EngineQueryWrapper<T> like(SFunction<T, ?> column, Object value) {
        super.like(column, value);
        return this;
    }

    /**
     * likeleft
    */
    @Override
    public EngineQueryWrapper<T> likeLeft(SFunction<T, ?> column, Object value) {
        super.likeLeft(column, value);
        return this;
    }

    /**
     * likeright
    */
    @Override
    public EngineQueryWrapper<T> likeRight(SFunction<T, ?> column, Object value) {
        super.likeRight(column, value);
        return this;
    }

    /**
     * 入
    */
    @Override
    public EngineQueryWrapper<T> in(SFunction<T, ?> column, Collection<?> values) {
        super.in(column, values);
        return this;
    }

    /**
     * not入
    */
    @Override
    public EngineQueryWrapper<T> notIn(SFunction<T, ?> column, Collection<?> values) {
        super.notIn(column, values);
        return this;
    }

    /**
     * 是否空
    */
    @Override
    public EngineQueryWrapper<T> isNull(SFunction<T, ?> column) {
        super.isNull(column);
        return this;
    }

    /**
     * 是否not空
    */
    @Override
    public EngineQueryWrapper<T> isNotNull(SFunction<T, ?> column) {
        super.isNotNull(column);
        return this;
    }

    /**
     * Between
    */
    @Override
    public EngineQueryWrapper<T> between(SFunction<T, ?> column, Object start, Object end) {
        super.between(column, start, end);
        return this;
    }

    /**
     * 订单byasc
    */
    @Override
    public EngineQueryWrapper<T> orderByAsc(SFunction<T, ?> column) {
        super.orderByAsc(column);
        return this;
    }

    /**
     * 订单bydesc
    */
    @Override
    public EngineQueryWrapper<T> orderByDesc(SFunction<T, ?> column) {
        super.orderByDesc(column);
        return this;
    }

    /**
     * table别名
    */
    @Override
    public EngineQueryWrapper<T> tableAlias(String alias) {
        super.tableAlias(alias);
        return this;
    }

    /**
     * 限制返回行数
    */
    @Override
    public EngineQueryWrapper<T> limit(int limit) {
        super.limit(limit);
        return this;
    }

    /**
     * 设置偏移行数
    */
    @Override
    public EngineQueryWrapper<T> offset(int offset) {
        super.offset(offset);
        return this;
    }

    /**
     * 清除返回行数上限（恢复为不限制）。
     *
     * @return this
     */
    @Override
    public EngineQueryWrapper<T> clearLimit() {
        super.clearLimit();
        return this;
    }

    /**
     * 清除偏移行数（恢复为不偏移）。
     *
     * @return this
     */
    @Override
    public EngineQueryWrapper<T> clearOffset() {
        super.clearOffset();
        return this;
    }

    /**
     * Lambda 投影列
    */
    @Override
    public EngineQueryWrapper<T> select(SFunction<T, ?> column) {
        super.select(column);
        return this;
    }

    /**
     * 字符串投影列
    */
    @Override
    public EngineQueryWrapper<T> select(String... columns) {
        super.select(columns);
        return this;
    }

    /**
     * 聚合函数投影列
    */
    @Override
    public EngineQueryWrapper<T> selectFunc(String function, String column, String alias) {
        super.selectFunc(function, column, alias);
        return this;
    }

    /**
     * COUNT(*) 聚合投影列
    */
    @Override
    public EngineQueryWrapper<T> selectCount(String alias) {
        super.selectCount(alias);
        return this;
    }

    /**
     * SUM 聚合投影列
    */
    @Override
    public EngineQueryWrapper<T> selectSum(String column, String alias) {
        super.selectSum(column, alias);
        return this;
    }

    /**
     * AVG 聚合投影列
    */
    @Override
    public EngineQueryWrapper<T> selectAvg(String column, String alias) {
        super.selectAvg(column, alias);
        return this;
    }

    /**
     * MAX 聚合投影列
    */
    @Override
    public EngineQueryWrapper<T> selectMax(String column, String alias) {
        super.selectMax(column, alias);
        return this;
    }

    /**
     * MIN 聚合投影列
    */
    @Override
    public EngineQueryWrapper<T> selectMin(String column, String alias) {
        super.selectMin(column, alias);
        return this;
    }

    /**
     * 单列分组
    */
    @Override
    public EngineQueryWrapper<T> groupBy(SFunction<T, ?> column) {
        super.groupBy(column);
        return this;
    }

    @Override
    @SafeVarargs
    /**
     * group By
     * @param columns 参数
     * @return 返回值
    */
    public final EngineQueryWrapper<T> groupBy(String... columns) {
        super.groupBy(columns);
        return this;
    }

    /**
     * HAVING 分组过滤条件
    */
    @Override
    public EngineQueryWrapper<T> having(String condition, Object... params) {
        super.having(condition, params);
        return this;
    }

    /**
     * INNER JOIN 关联
    */
    @Override
    public EngineQueryWrapper<T> innerJoin(String table, String onCondition) {
        super.innerJoin(table, onCondition);
        return this;
    }

    /**
     * INNER JOIN 关联（显式别名）
    */
    @Override
    public EngineQueryWrapper<T> innerJoin(String table, String alias, String onCondition) {
        super.innerJoin(table, alias, onCondition);
        return this;
    }

    /**
     * LEFT JOIN 关联
    */
    @Override
    public EngineQueryWrapper<T> leftJoin(String table, String onCondition) {
        super.leftJoin(table, onCondition);
        return this;
    }

    /**
     * LEFT JOIN 关联（显式别名）
    */
    @Override
    public EngineQueryWrapper<T> leftJoin(String table, String alias, String onCondition) {
        super.leftJoin(table, alias, onCondition);
        return this;
    }

    /**
     * RIGHT JOIN 关联
    */
    @Override
    public EngineQueryWrapper<T> rightJoin(String table, String onCondition) {
        super.rightJoin(table, onCondition);
        return this;
    }

    /**
     * RIGHT JOIN 关联（显式别名）
    */
    @Override
    public EngineQueryWrapper<T> rightJoin(String table, String alias, String onCondition) {
        super.rightJoin(table, alias, onCondition);
        return this;
    }

    /**
     * 列表
    */
    @Override
    public List<T> list() {
        return engine.executeQuery(this, entityClass);
    }

    /**
     * One
    */
    @Override
    public T one() {
        List<T> list = list();
        if (list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    /**
     * Page
    */
    @Override
    public Page<T> page(int pn, int ps) {
        return engine.executePage(this, entityClass, pn, ps);
    }

    /**
     * 统计当前条件命中的总行数
    */
    @Override
    public long count() {
        return engine.queryCount(this);
    }
}
