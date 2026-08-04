package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lambda 更新包装器，提供类似 MyBatis-Plus 的链式 SET 和 WHERE 条件构建功能。
 * <p>
 * 支持：
 * <ul>
 *   <li>SET 子句 — {@link #set(SFunction, Object)} / {@link #set(String, Object)}</li>
 *   <li>WHERE 条件 — 继承自 {@link AbstractLambdaWrapper} 的所有条件方法</li>
 *   <li>SQL 构建 — {@link #buildSql()} 生成结构化的更新 SQL 信息</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * UpdateSql sql = engine.update(User.class)
 *     .set(User::getName, "新名称")
 *     .set(User::getAge, 25)
 *     .eq(User::getId, 1)
 *     .buildSql();
 *
 * // sql.setClause()   → "name = ?, age = ?"
 * // sql.whereClause() → "id = ?"
 * // sql.params()      → ["新名称", 25, 1]
 * }</pre>
 * </p>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 2024/12/12
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class LambdaUpdateWrapper<T> extends AbstractLambdaWrapper<T, LambdaUpdateWrapper<T>> {

    /** SET 值映射：列名 → 新值 */
    private final Map<String, Object> setValues = new LinkedHashMap<>();

    public LambdaUpdateWrapper(Class<T> entityClass) {
        super(entityClass);
    }

    /**
     * 添加 SET 列和值（Lambda 方式）。
     *
     * @param column 列的方法引用
     * @param value  新值
     * @return this
     */
    public LambdaUpdateWrapper<T> set(SFunction<T, ?> column, Object value) {
        setValues.put(resolveColumn(column), value);
        return this;
    }

    /**
     * 添加 SET 列和值（字符串方式）。
     *
     * @param column 列名
     * @param value  新值
     * @return this
     */
    public LambdaUpdateWrapper<T> set(String column, Object value) {
        setValues.put(column, value);
        return this;
    }

    /**
     * 获取 SET 值映射。
     *
     * @return SET 值映射（列名 → 新值）
     */
    public Map<String, Object> getSetValues() {
        return setValues;
    }

    /**
     * 将当前链式 API 构建的条件渲染为结构化的更新 SQL 信息。
     * <p>
     * 返回的 {@link UpdateSql} 记录了 SET 子句、WHERE 子句和参数列表，
     * 参数顺序为：先 SET 值，后 WHERE 值。
     * </p>
     *
     * @return 更新 SQL 信息
     */
    public UpdateSql buildSql() {
        List<Object> params = new ArrayList<>();
        StringBuilder setSb = new StringBuilder();
        for (Map.Entry<String, Object> e : setValues.entrySet()) {
            if (!setSb.isEmpty()) {
                setSb.append(", ");
            }
            setSb.append(e.getKey()).append(" = ?");
            params.add(e.getValue());
        }

        StringBuilder where = new StringBuilder();
        buildWhere(where, params);

        return new UpdateSql(entityClass, setSb.toString(), where.toString(), params);
    }

    // ==================== 终端执行方法 ====================

    /**
     * 执行更新操作。
     * <p>由 {@code Engine} 实现类重写，生成 UPDATE SQL 并执行。</p>
     *
     * @return 受影响行数
     */
    public int update() {
        throw new UnsupportedOperationException("update() 需由引擎实现类重写");
    }

    /**
     * 保存或更新实体。
     * <p>直接委托给 {@link #update()}，由 Engine 实现类根据实体 ID 判断 INSERT 或 UPDATE。</p>
     *
     * @param entity 实体实例
     * @return 受影响行数
     */
    public int saveOrUpdate(T entity) {
        return update();
    }

    @Override
    protected LambdaUpdateWrapper<T> newInstance() {
        return new LambdaUpdateWrapper<>(entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return null;
    }

    /**
     * 构建 WHERE 子句和参数列表。
     * <p>遍历所有条件，用 AND 连接，参数追加到已有参数列表之后。</p>
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