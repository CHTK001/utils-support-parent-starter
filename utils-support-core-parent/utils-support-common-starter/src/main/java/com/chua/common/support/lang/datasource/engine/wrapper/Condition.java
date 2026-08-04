package com.chua.common.support.lang.datasource.engine.wrapper;

import lombok.Getter;

import java.util.List;

/**
 * 条件值对象，表示一个 WHERE 条件的结构化描述。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>简单条件 — 包含 {@code column}、{@code operator}、{@code value}</li>
 *   <li>嵌套条件 — 包含 {@code nested} 子条件列表和 {@code nestedOperator}（AND / OR）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 * @see AbstractLambdaWrapper
 */
@Getter
@SuppressWarnings("NullAway")
@NullUnmarked
public class Condition {

    /**
     * Lambda 方法引用（简单条件）
     */
    private SFunction<?, ?> column;

    /**
     * 解析后的列名（由子类调用 {@link #setColumnName} 填充）
     */
    private String columnName;

    /**
     * SQL 操作符（=、!=、>、LIKE、IN 等）
     */
    private final String operator;

    /**
     * 参数值
     */
    private final Object value;

    /**
     * 嵌套子条件列表（嵌套条件）
     */
    private final List<Condition> nested;

    /**
     * 嵌套子条件的连接符（AND / OR）
     */
    private final String nestedOperator;

    Condition(SFunction<?, ?> column, String operator, Object value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
        this.nested = null;
        this.nestedOperator = null;
    }

    Condition(List<Condition> nested, String nestedOperator) {
        this.column = null;
        this.operator = null;
        this.value = null;
        this.nested = nested;
        this.nestedOperator = nestedOperator;
    }

    /**
     * 设置解析后的列名
     *
     * @param columnName 列名字符串
     */
    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    /**
     * 判断当前条件是否为嵌套条件
     *
     * @return true 表示为嵌套条件，否则为简单条件
     */
    public boolean isNested() {
        return nested != null;
    }

    // ---------------------------------------------------------------
    // 公共工厂方法（供 CalciteDataTableAdapter 等跨包使用）
    // ---------------------------------------------------------------

    /**
     * 创建以列名字符串表示的简单条件
     *
     * @param columnName 列名
     * @param operator   操作符
     * @param value      参数值
     * @return Condition 实例
     */
    public static Condition of(String columnName, String operator, Object value) {
        Condition condition = new Condition((SFunction<?, ?>) null, operator, value);
        condition.columnName = columnName;
        return condition;
    }

    /**
     * 创建 AND 嵌套条件
     *
     * @param conditions 子条件列表
     * @return Condition 实例
     */
    public static Condition and(List<Condition> conditions) {
        return new Condition(conditions, "AND");
    }

    /**
     * 创建 OR 嵌套条件
     *
     * @param conditions 子条件列表
     * @return Condition 实例
     */
    public static Condition or(List<Condition> conditions) {
        return new Condition(conditions, "OR");
    }
}