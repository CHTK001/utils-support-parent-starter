package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lambda 删除包装器，提供类似 MyBatis-Plus 的链式 WHERE 条件构建功能。
 * <p>
 * 支持：
 * <ul>
 *   <li>WHERE 条件 — 继承自 {@link AbstractLambdaWrapper} 的所有条件方法</li>
 *   <li>SQL 构建 — {@link #buildSql()} 生成结构化的删除 SQL 信息</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * DeleteSql sql = engine.delete(User.class)
 *     .eq(User::getId, 1)
 *     .buildSql();
 *
 * // sql.whereClause() → "id = ?"
 * // sql.params()      → [1]
 * }</pre>
 * </p>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 2024/12/12
 */
public class LambdaDeleteWrapper<T> extends AbstractLambdaWrapper<T, LambdaDeleteWrapper<T>> {

    public LambdaDeleteWrapper(Class<T> entityClass) {
        super(entityClass);
    }

    /**
     * 将当前链式 API 构建的条件渲染为结构化的删除 SQL 信息。
     * <p>
     * 返回的 {@link DeleteSql} 记录了 WHERE 子句和参数列表。
     * </p>
     *
     * @return 删除 SQL 信息
     */
    public DeleteSql buildSql() {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            renderCondition(where, params, conditions.get(i));
        }
        return new DeleteSql(entityClass, where.toString(), params);
    }

    // ==================== 终端执行方法 ====================

    /**
     * 执行删除操作。
     * <p>由 {@code Engine} 实现类重写，生成 DELETE SQL 并执行。</p>
     *
     * @return 受影响行数
     */
    public int remove() {
        throw new UnsupportedOperationException("remove() 需由引擎实现类重写");
    }

    @Override
    protected LambdaDeleteWrapper<T> newInstance() {
        return new LambdaDeleteWrapper<>(entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return null;
    }

    /**
     * 渲染单个条件为 SQL 片段。
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
        sb.append(col).append(" ").append(c.getOperator()).append(" ?");
        params.add(c.getValue());
    }

}
