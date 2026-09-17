package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.*;
import java.util.function.Consumer;

/**
* Lambda 抽象包装器，提供类似 MyBatis-Plus 的链式条件 API。
* <p>
* 该抽象类定义了所有 Lambda 包装器共用的条件构建方法，包括：
* <ul>
*   <li>比较操作 — {@link #eq}, {@link #ne}, {@link #gt}, {@link #ge}, {@link #lt}, {@link #le}</li>
*   <li>模糊匹配 — {@link #like}, {@link #likeLeft}, {@link #likeRight}</li>
*   <li>范围操作 — {@link #in}, {@link #notIn}, {@link #between}</li>
*   <li>空值判断 — {@link #isNull}, {@link #isNotNull}</li>
*   <li>逻辑分组 — {@link #and}, {@link #or}</li>
*   <li>排序 — {@link #orderByAsc}, {@link #orderByDesc}</li>
* </ul>
* </p>
* <p>
* 条件以结构化的 {@link Condition} 对象列表存储，不直接拼接 SQL 字符串，
* 由子类通过 {@link #buildSql()} 方法统一渲染为 SQL 语句。
* 这样可以在渲染阶段引用实体元数据和方言信息，生成语法正确的 SQL。
* </p>
* <p>
* 子类需要实现：
* <ul>
*   <li>{@link #newInstance()} — 创建同类型的新包装器实例（用于 and/or 嵌套）</li>
*   <li>{@link #resolveColumn(SFunction)} — 将 Lambda 方法引用解析为数据库列名</li>
* </ul>
* </p>
*
* @param <T> 实体类型
* @param <C> 子类类型（CRTP 模式，支持链式调用返回子类类型）
* @author CH
* @since 2024/12/12
* @see LambdaQueryWrapper
* @see LambdaUpdateWrapper
* @see LambdaDeleteWrapper
 */
@SuppressWarnings("unchecked")
public abstract class AbstractLambdaWrapper<T, C extends AbstractLambdaWrapper<T, C>> {

    /** 实体类类型 */
    protected final Class<T> entityClass;

    /** 条件列表，每个条件是一个列名 + 操作符 + 值的组合 */
    protected final List<Condition> conditions = new ArrayList<>();

    /** 排序列列表，每项格式为 "列名 ASC" 或 "列名 DESC" */
    protected final List<String> orderBys = new ArrayList<>();

    /** 表别名，用于多表关联查询 */
    protected String tableAlias;

    /**
    * 创建 AbstractLambdaWrapper 实例
    * @param entityClass entityClass
    */
    protected AbstractLambdaWrapper(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    // ==================== 条件 API ====================

    /** 创建Condition */
    private Condition createCondition(SFunction<T, ?> column, String operator, Object value) {
        Condition c = new Condition(column, operator, value);
        c.setColumnName(resolveColumn(column));
        return c;
    }

    /** 等于（=） */
    public C eq(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "=", value));
        return (C) this;
    }

    /** 等于（=）（字符串列名方式） */
    public C eq(String column, Object value) {
        conditions.add(Condition.of(column, "=", value));
        return (C) this;
    }

    /** 不等于（!=） */
    public C ne(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "!=", value));
        return (C) this;
    }

    /** 不等于（!=）（字符串列名方式） */
    public C ne(String column, Object value) {
        conditions.add(Condition.of(column, "!=", value));
        return (C) this;
    }

    /** 大于（>） */
    public C gt(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, ">", value));
        return (C) this;
    }

    /** 大于（>）（字符串列名方式） */
    public C gt(String column, Object value) {
        conditions.add(Condition.of(column, ">", value));
        return (C) this;
    }

    /** 大于等于（>=） */
    public C ge(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, ">=", value));
        return (C) this;
    }

    /** 大于等于（>=）（字符串列名方式） */
    public C ge(String column, Object value) {
        conditions.add(Condition.of(column, ">=", value));
        return (C) this;
    }

    /** 小于（<） */
    public C lt(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "<", value));
        return (C) this;
    }

    /** 小于（<）（字符串列名方式） */
    public C lt(String column, Object value) {
        conditions.add(Condition.of(column, "<", value));
        return (C) this;
    }

    /** 小于等于（<=） */
    public C le(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "<=", value));
        return (C) this;
    }

    /** 小于等于（<=）（字符串列名方式） */
    public C le(String column, Object value) {
        conditions.add(Condition.of(column, "<=", value));
        return (C) this;
    }

    /** 模糊匹配（LIKE %value%） */
    public C like(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "LIKE", "%" + value + "%"));
        return (C) this;
    }

    /** 模糊匹配（LIKE %value%）（字符串列名方式） */
    public C like(String column, Object value) {
        conditions.add(Condition.of(column, "LIKE", "%" + value + "%"));
        return (C) this;
    }

    /** 左模糊匹配（LIKE %value） */
    public C likeLeft(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "LIKE", "%" + value));
        return (C) this;
    }

    /** 左模糊匹配（LIKE %value）（字符串列名方式） */
    public C likeLeft(String column, Object value) {
        conditions.add(Condition.of(column, "LIKE", "%" + value));
        return (C) this;
    }

    /** 右模糊匹配（LIKE value%） */
    public C likeRight(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "LIKE", value + "%"));
        return (C) this;
    }

    /** 右模糊匹配（LIKE value%）（字符串列名方式） */
    public C likeRight(String column, Object value) {
        conditions.add(Condition.of(column, "LIKE", value + "%"));
        return (C) this;
    }

    /** IN 查询 */
    public C in(SFunction<T, ?> column, Collection<?> values) {
        conditions.add(createCondition(column, "IN", values));
        return (C) this;
    }

    /** IN 查询（字符串列名方式） */
    public C in(String column, Collection<?> values) {
        conditions.add(Condition.of(column, "IN", values));
        return (C) this;
    }

    /** NOT IN 查询 */
    public C notIn(SFunction<T, ?> column, Collection<?> values) {
        conditions.add(createCondition(column, "NOT IN", values));
        return (C) this;
    }

    /** NOT IN 查询（字符串列名方式） */
    public C notIn(String column, Collection<?> values) {
        conditions.add(Condition.of(column, "NOT IN", values));
        return (C) this;
    }

    /** IS NULL 判断 */
    public C isNull(SFunction<T, ?> column) {
        conditions.add(createCondition(column, "IS NULL", null));
        return (C) this;
    }

    /** IS NULL 判断（字符串列名方式） */
    public C isNull(String column) {
        conditions.add(Condition.of(column, "IS NULL", null));
        return (C) this;
    }

    /** IS NOT NULL 判断 */
    public C isNotNull(SFunction<T, ?> column) {
        conditions.add(createCondition(column, "IS NOT NULL", null));
        return (C) this;
    }

    /** IS NOT NULL 判断（字符串列名方式） */
    public C isNotNull(String column) {
        conditions.add(Condition.of(column, "IS NOT NULL", null));
        return (C) this;
    }

    /** BETWEEN 范围查询 */
    public C between(SFunction<T, ?> column, Object start, Object end) {
        conditions.add(createCondition(column, "BETWEEN", new Object[]{start, end}));
        return (C) this;
    }

    /** BETWEEN 范围查询（字符串列名方式） */
    public C between(String column, Object start, Object end) {
        conditions.add(Condition.of(column, "BETWEEN", new Object[]{start, end}));
        return (C) this;
    }

    /** AND 逻辑分组，括号包裹一组条件 */
    public C and(Consumer<C> andGroup) {
        C sub = newInstance();
        andGroup.accept(sub);
        conditions.add(new Condition(sub.conditions, "AND"));
        return (C) this;
    }

    /** OR 逻辑分组，括号包裹一组条件 */
    public C or(Consumer<C> orGroup) {
        C sub = newInstance();
        orGroup.accept(sub);
        conditions.add(new Condition(sub.conditions, "OR"));
        return (C) this;
    }

    /** 升序排序 */
    public C orderByAsc(SFunction<T, ?> column) {
        orderBys.add(resolveColumn(column) + " ASC");
        return (C) this;
    }

    /** 升序排序（字符串列名方式） */
    public C orderByAsc(String column) {
        orderBys.add(column + " ASC");
        return (C) this;
    }

    /** 降序排序 */
    public C orderByDesc(SFunction<T, ?> column) {
        orderBys.add(resolveColumn(column) + " DESC");
        return (C) this;
    }

    /** 降序排序（字符串列名方式） */
    public C orderByDesc(String column) {
        orderBys.add(column + " DESC");
        return (C) this;
    }

    /** 设置表别名 */
    public C tableAlias(String alias) {
        this.tableAlias = alias;
        return (C) this;
    }

    // ==================== 子类扩展点 ====================

    /**
    * 创建同类型的新包装器实例，用于 and/or 嵌套条件。
    *
    * @return 新实例
    */
    protected abstract C newInstance();

    /**
    * 将 Lambda 方法引用解析为数据库列名。
    * <p>由子类或外部 {@code LambdaUtils} 实现，
    * 通过解析 {@link java.lang.invoke.SerializedLambda} 字节码获取属性名，
    * 再根据实体元数据映射为数据库列名。</p>
    *
    * @param column Lambda 方法引用
    * @return 数据库列名
    */
    protected abstract String resolveColumn(SFunction<T, ?> column);

    // ==================== 访问器 ====================

    /** 获取EntityClass */
    public Class<T> getEntityClass() {
        return entityClass;
    }

    /** 获取Conditions */
    public List<Condition> getConditions() {
        return conditions;
    }

    /** 获取OrderBys */
    public List<String> getOrderBys() {
        return orderBys;
    }

}
