package com.chua.common.support.lang.datasource.engine.wrapper;

import com.chua.common.support.lang.datasource.page.Page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Lambda 查询包装器，提供类似 MyBatis-Plus 的链式查询条件构建和 SQL 生成功能。
 * <p>
 * 支持的操作：
 * <ul>
 *   <li>SELECT 列指定 — {@link #select(SFunction)}</li>
 *   <li>WHERE 条件 — 继承自 {@link AbstractLambdaWrapper}</li>
 *   <li>GROUP BY — {@link #groupBy(SFunction)}</li>
 *   <li>ORDER BY — 继承自 {@link AbstractLambdaWrapper}</li>
 *   <li>SQL 构建 — {@link #buildSql()} 生成结构化的查询 SQL 信息</li>
 * </ul>
 * </p>
 * <p>
 * 该类的 {@code buildSql()} 方法将链式 API 构建的条件列表渲染为 SQL 片段，
 * 生成 {@link QuerySql} 记录对象，包含 SELECT 列、WHERE 子句、参数列表、
 * GROUP BY 列和 ORDER BY 列表，由 {@code Engine} 或 {@code SqlExecutor} 执行。
 * </p>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 2024/12/12
 * @see AbstractLambdaWrapper
 * @see LambdaUpdateWrapper
 * @see LambdaDeleteWrapper
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class LambdaQueryWrapper<T> extends AbstractLambdaWrapper<T, LambdaQueryWrapper<T>> {

    private final List<String> selectColumns = new ArrayList<>();
    private String groupByColumn;

    public LambdaQueryWrapper(Class<T> entityClass) {
        super(entityClass);
    }

    // ==================== SELECT ====================

    /**
     * 添加 SELECT 列。
     *
     * @param column Lambda 方法引用
     * @return this
     */
    public LambdaQueryWrapper<T> select(SFunction<T, ?> column) {
        selectColumns.add(resolveColumn(column));
        return this;
    }

    /**
     * 批量添加 SELECT 列。
     *
     * @param columns Lambda 方法引用数组
     * @return this
     */
    @SafeVarargs
    public final LambdaQueryWrapper<T> select(SFunction<T, ?>... columns) {
        for (SFunction<T, ?> c : columns) {
            select(c);
        }
        return this;
    }

    /**
     * 以字符串形式添加 SELECT 列（非 Lambda 方式）。
     *
     * @param columns 列名数组
     * @return this
     */
    public LambdaQueryWrapper<T> select(String... columns) {
        selectColumns.addAll(List.of(columns));
        return this;
    }

    // ==================== GROUP BY ====================

    /**
     * 添加 GROUP BY 列。
     *
     * @param column Lambda 方法引用
     * @return this
     */
    public LambdaQueryWrapper<T> groupBy(SFunction<T, ?> column) {
        this.groupByColumn = resolveColumn(column);
        return this;
    }

    // ==================== SQL 构建 ====================

    /**
     * 将当前链式 API 构建的条件渲染为结构化的查询 SQL 信息。
     * <p>
     * 返回的 {@link QuerySql} 记录了 SELECT 列、WHERE 子句、参数列表等，
     * 可由 Engine 或外部处理器组合成完整的 SQL 语句并执行。
     * </p>
     *
     * @return 查询 SQL 信息
     */
    public QuerySql buildSql() {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        buildWhere(where, params);
        return new QuerySql(entityClass, selectColumns, where.toString(), params, groupByColumn, getOrderBys());
    }

    // ==================== 内部实现 ====================

    @Override
    protected LambdaQueryWrapper<T> newInstance() {
        return new LambdaQueryWrapper<>(entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return null;
    }

    /**
     * 构建 WHERE 子句和参数列表。
     * <p>遍历所有条件，用 AND 连接。</p>
     */
    protected void buildWhere(StringBuilder sb, List<Object> params) {
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            renderCondition(sb, params, conditions.get(i));
        }
    }

    /**
     * 渲染单个条件为 SQL 片段。
     * <p>处理嵌套条件（括号包裹）、IN/BETWEEN 等特殊语法。</p>
     */
    protected void renderCondition(StringBuilder sb, List<Object> params, Condition c) {
        if (c.isNested()) {
            sb.append("(");
            for (int i = 0; i < c.getNested().size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(c.getNestedOperator()).append(" ");
                }
                renderCondition(sb, params, c.getNested().get(i));
            }
            sb.append(")");
            return;
        }
        String col = c.getColumnName();
        if (col == null) {
            col = "?";
        }
        sb.append(col);
        switch (c.getOperator()) {
            case "IS NULL":
            case "IS NOT NULL":
                sb.append(" ").append(c.getOperator());
                break;
            case "IN":
            case "NOT IN":
                sb.append(" ").append(c.getOperator()).append(" (");
                Collection<?> vals = (Collection<?>) c.getValue();
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
            case "BETWEEN":
                Object[] range = (Object[]) c.getValue();
                sb.append(" BETWEEN ? AND ?");
                params.add(range[0]);
                params.add(range[1]);
                break;
            default:
                sb.append(" ").append(c.getOperator()).append(" ?");
                params.add(c.getValue());
                break;
        }
    }

    // ==================== 终端执行方法 ====================

    /**
     * 执行查询，返回实体列表。
     * <p>由 {@code Engine} 实现类重写，实际执行 SQL 并映射结果。</p>
     *
     * @return 实体列表
     */
    public List<T> list() {
        throw new UnsupportedOperationException("list() 需由引擎实现类重写");
    }

    /**
     * 执行查询，返回单个实体。
     * <p>由 {@code Engine} 实现类重写。如果结果多于一条，返回第一条。</p>
     *
     * @return 实体，不存在返回 null
     */
    public T one() {
        throw new UnsupportedOperationException("one() 需由引擎实现类重写");
    }

    /**
     * 执行分页查询。
     * <p>由 {@code Engine} 实现类重写。</p>
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public Page<T> page(int pageNum, int pageSize) {
        throw new UnsupportedOperationException("page() 需由引擎实现类重写");
    }

}