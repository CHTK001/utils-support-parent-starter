package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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

    /**
     * 实体类类型
    */
    protected final Class<T> entityClass;

    /**
     * 条件列表，每个条件是一个列名 + 操作符 + 值的组合
    */
    protected final List<Condition> conditions = new ArrayList<>();

    /**
     * 排序列列表，每项格式为 "列名 ASC" 或 "列名 DESC"
    */
    protected final List<String> orderBys = new ArrayList<>();

    /**
     * 表别名，用于多表关联查询
    */
    protected String tableAlias;

    /**
     * 是否允许无 WHERE 条件的全表更新/删除（allowFullTable() 显式开启）
    */
    protected boolean fullTableAllowed;

    /**
     * 合法 SQL 标识符：字母/数字/下划线，允许一级表限定（如 user_name、t.user_name）
    */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    /**
     * 创建 AbstractLambdaWrapper 实例
     * @param entityClass entityClass
     */
    protected AbstractLambdaWrapper(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    // ==================== 条件 API ====================

    /**
     * 创建Condition
     * @param column 列，不允许为 null
     * @param operator 方法入参 operator
     * @param value 值，不允许为 null
     * @return Condition 对象
     */
    private Condition createCondition(SFunction<T, ?> column, String operator, Object value) {
        Condition c = new Condition(column, operator, value);
        c.setColumnName(resolveColumn(column));
        return c;
    }

    /**
     * 等于（=）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C eq(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "=", value));
        return (C) this;
    }

    /**
     * 等于（=）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C eq(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "=", value));
        return (C) this;
    }

    /**
     * 不等于（!=）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C ne(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "!=", value));
        return (C) this;
    }

    /**
     * 不等于（!=）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C ne(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "!=", value));
        return (C) this;
    }

    /**
     * 大于（>）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C gt(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, ">", value));
        return (C) this;
    }

    /**
     * 大于（>）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C gt(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), ">", value));
        return (C) this;
    }

    /**
     * 大于等于（>=）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C ge(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, ">=", value));
        return (C) this;
    }

    /**
     * 大于等于（>=）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C ge(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), ">=", value));
        return (C) this;
    }

    /**
     * 小于（<）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C lt(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "<", value));
        return (C) this;
    }

    /**
     * 小于（<）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C lt(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "<", value));
        return (C) this;
    }

    /**
     * 小于等于（<=）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C le(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "<=", value));
        return (C) this;
    }

    /**
     * 小于等于（<=）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C le(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "<=", value));
        return (C) this;
    }

    /**
     * 模糊匹配（LIKE %value%）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C like(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "LIKE", "%" + value + "%"));
        return (C) this;
    }

    /**
     * 模糊匹配（LIKE %value%）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C like(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "LIKE", "%" + value + "%"));
        return (C) this;
    }

    /**
     * 左模糊匹配（LIKE %value）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C likeLeft(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "LIKE", "%" + value));
        return (C) this;
    }

    /**
     * 左模糊匹配（LIKE %value）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C likeLeft(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "LIKE", "%" + value));
        return (C) this;
    }

    /**
     * 右模糊匹配（LIKE value%）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C likeRight(SFunction<T, ?> column, Object value) {
        conditions.add(createCondition(column, "LIKE", value + "%"));
        return (C) this;
    }

    /**
     * 右模糊匹配（LIKE value%）（字符串列名方式）
     * @param column 列，不允许为 null
     * @param value 值，不允许为 null
     * @return C 对象
     */
    public C likeRight(String column, Object value) {
        conditions.add(Condition.of(checkIdentifier(column), "LIKE", value + "%"));
        return (C) this;
    }

    /**
     * IN 查询
     * @param column 列，不允许为 null
     * @param values 方法入参 values
     * @return C 对象
     */
    public C in(SFunction<T, ?> column, Collection<?> values) {
        conditions.add(createCondition(column, "IN", values));
        return (C) this;
    }

    /**
     * IN 查询（字符串列名方式）
     * @param column 列，不允许为 null
     * @param values 方法入参 values
     * @return C 对象
     */
    public C in(String column, Collection<?> values) {
        conditions.add(Condition.of(checkIdentifier(column), "IN", values));
        return (C) this;
    }

    /**
     * NOT IN 查询
     * @param column 列，不允许为 null
     * @param values 方法入参 values
     * @return C 对象
     */
    public C notIn(SFunction<T, ?> column, Collection<?> values) {
        conditions.add(createCondition(column, "NOT IN", values));
        return (C) this;
    }

    /**
     * NOT IN 查询（字符串列名方式）
     * @param column 列，不允许为 null
     * @param values 方法入参 values
     * @return C 对象
     */
    public C notIn(String column, Collection<?> values) {
        conditions.add(Condition.of(checkIdentifier(column), "NOT IN", values));
        return (C) this;
    }

    /**
     * IS NULL 判断
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C isNull(SFunction<T, ?> column) {
        conditions.add(createCondition(column, "IS NULL", null));
        return (C) this;
    }

    /**
     * IS NULL 判断（字符串列名方式）
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C isNull(String column) {
        conditions.add(Condition.of(checkIdentifier(column), "IS NULL", null));
        return (C) this;
    }

    /**
     * IS NOT NULL 判断
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C isNotNull(SFunction<T, ?> column) {
        conditions.add(createCondition(column, "IS NOT NULL", null));
        return (C) this;
    }

    /**
     * IS NOT NULL 判断（字符串列名方式）
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C isNotNull(String column) {
        conditions.add(Condition.of(checkIdentifier(column), "IS NOT NULL", null));
        return (C) this;
    }

    /**
     * BETWEEN 范围查询
     * @param column 列，不允许为 null
     * @param start 启动，不允许为 null
     * @param end 结束，不允许为 null
     * @return C 对象
     */
    public C between(SFunction<T, ?> column, Object start, Object end) {
        conditions.add(createCondition(column, "BETWEEN", new Object[]{start, end}));
        return (C) this;
    }

    /**
     * BETWEEN 范围查询（字符串列名方式）
     * @param column 列，不允许为 null
     * @param start 启动，不允许为 null
     * @param end 结束，不允许为 null
     * @return C 对象
     */
    public C between(String column, Object start, Object end) {
        conditions.add(Condition.of(checkIdentifier(column), "BETWEEN", new Object[]{start, end}));
        return (C) this;
    }

    /**
     * AND 逻辑分组，括号包裹一组条件
     * @param andGroup and分组，不允许为 null
     * @return C 对象
     */
    public C and(Consumer<C> andGroup) {
        C sub = newInstance();
        andGroup.accept(sub);
        conditions.add(new Condition(sub.conditions, "AND"));
        return (C) this;
    }

    /**
     * OR 逻辑分组，括号包裹一组条件
     * @param orGroup or分组，不允许为 null
     * @return C 对象
     */
    public C or(Consumer<C> orGroup) {
        C sub = newInstance();
        orGroup.accept(sub);
        conditions.add(new Condition(sub.conditions, "OR"));
        return (C) this;
    }

    /**
     * 升序排序
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C orderByAsc(SFunction<T, ?> column) {
        orderBys.add(resolveColumn(column) + " ASC");
        return (C) this;
    }

    /**
     * 升序排序（字符串列名方式）
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C orderByAsc(String column) {
        orderBys.add(checkIdentifier(column) + " ASC");
        return (C) this;
    }

    /**
     * 降序排序
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C orderByDesc(SFunction<T, ?> column) {
        orderBys.add(resolveColumn(column) + " DESC");
        return (C) this;
    }

    /**
     * 降序排序（字符串列名方式）
     * @param column 列，不允许为 null
     * @return C 对象
     */
    public C orderByDesc(String column) {
        orderBys.add(checkIdentifier(column) + " DESC");
        return (C) this;
    }

    /**
     * 设置表别名
     * @param alias 方法入参 alias
     * @return C 对象
     */
    public C tableAlias(String alias) {
        this.tableAlias = checkIdentifier(alias);
        return (C) this;
    }

    /**
     * 显式允许无 WHERE 条件的全表更新/删除。
     * <p>更新/删除包装器默认在 {@code buildSql()} 时拒绝空 WHERE，
     * 调用本方法后放行全表操作。</p>
     *
     * @return this
     */
    public C allowFullTable() {
        this.fullTableAllowed = true;
        return (C) this;
    }

    // ==================== WHERE 渲染（统一实现） ====================

    /**
     * 构建 WHERE 子句和参数列表。
     * <p>遍历所有条件，普通条件之间以 AND 连接；若当前条件为 OR 嵌套分组，
     * 则该分组与前文之间以 OR 连接（AND 嵌套分组仍以 AND 连接）。</p>
     *
     * @param sb     WHERE 片段缓冲
     * @param params 参数收集列表
     */
    protected void buildWhere(StringBuilder sb, List<Object> params) {
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                Condition current = conditions.get(i);
                // OR 嵌套分组需要与前文以 OR 连接，其余条件统一 AND
                boolean leadingOr = current.isNested()
                        && "OR".equalsIgnoreCase(current.getNestedOperator());
                sb.append(leadingOr ? " OR " : " AND ");
            }
            renderCondition(sb, params, conditions.get(i));
        }
    }

    /**
     * 渲染单个条件为 SQL 片段。
     * <p>处理嵌套条件（括号包裹）、IS NULL、IN/BETWEEN 等特殊语法；
     * 空集合的 IN 渲染为恒假条件 {@code 1 = 0}、NOT IN 渲染为恒真条件
     * {@code 1 = 1}，避免生成非法的 {@code IN ()}。</p>
     *
     * @param sb     SQL 片段缓冲
     * @param params 参数收集列表
     * @param c      待渲染条件
     */
    protected void renderCondition(StringBuilder sb, List<Object> params, Condition c) {
        if (c.isNested()) {
            List<Condition> nested = c.getNested();
            if (nested == null || nested.isEmpty()) {
                sb.append("1 = 1");
                return;
            }
            sb.append("(");
            for (int i = 0; i < nested.size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(c.getNestedOperator()).append(" ");
                }
                renderCondition(sb, params, nested.get(i));
            }
            sb.append(")");
            return;
        }
        String col = c.getColumnName();
        if (col == null) {
            col = "?";
        }
        String operator = c.getOperator();
        switch (operator) {
            case "IS NULL":
            case "IS NOT NULL":
                sb.append(col).append(" ").append(operator);
                break;
            case "IN":
            case "NOT IN": {
                Collection<?> vals = (Collection<?>) c.getValue();
                if (vals == null || vals.isEmpty()) {
                    sb.append("IN".equals(operator) ? "1 = 0" : "1 = 1");
                    break;
                }
                sb.append(col).append(" ").append(operator).append(" (");
                Iterator<?> it = vals.iterator();
                for (int i = 0; i < vals.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append("?");
                    params.add(it.next());
                }
                sb.append(")");
                break;
            }
            case "BETWEEN": {
                Object[] range = (Object[]) c.getValue();
                sb.append(col).append(" BETWEEN ? AND ?");
                params.add(range[0]);
                params.add(range[1]);
                break;
            }
            default:
                sb.append(col).append(" ").append(operator).append(" ?");
                params.add(c.getValue());
                break;
        }
    }

    // ==================== 标识符校验 ====================

    /**
     * 校验字符串方式传入的 SQL 标识符（列名、别名），拒绝空格、引号、分号等
     * 可拼接进 SQL 的字符，封闭字符串 API 的注入面。
     *
     * @param column 待校验标识符
     * @return 校验通过的标识符原值
     * @throws IllegalArgumentException 标识符为 null、空白或不满足白名单规则时抛出
     */
    protected static String checkIdentifier(String column) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("SQL 标识符不能为空");
        }
        if (!IDENTIFIER_PATTERN.matcher(column).matches()) {
            throw new IllegalArgumentException("非法 SQL 标识符: " + column);
        }
        return column;
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

    /**
     * 获取EntityClass
     * @return Class 对象
     */
    public Class<T> getEntityClass() {
        return entityClass;
    }

    /**
     * 获取Conditions
     * @return 结果列表，无数据时为空列表
     */
    public List<Condition> getConditions() {
        return conditions;
    }

    /**
     * 获取OrderBys
     * @return 结果列表，无数据时为空列表
     */
    public List<String> getOrderBys() {
        return orderBys;
    }

}
