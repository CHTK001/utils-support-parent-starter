package com.chua.common.support.lang.datasource.engine.wrapper;
import com.chua.common.support.collection.wrap.Wraps;
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

    /**
     * 创建 LambdaDeleteWrapper 实例
     * @param entityClass entityClass
     */
    public LambdaDeleteWrapper(Class<T> entityClass) {
        super(entityClass);
    }

    /**
     * 校验是否允许无 WHERE 条件执行全表操作。
     *
     * <p>守卫集中在此处，供 {@link #buildSql()} 与「不经过 SQL 渲染」的求值路径
     * （内存/文件引擎的 {@code remove()}）共用。后者若自行求值而不复用本方法，
     * 无 WHERE 的终端调用会静默作用于全表（曾使内存引擎的 {@code remove()} 清空整表）。</p>
     *
     * @param operation 操作名，用于异常文案（如「删除」）
     * @throws IllegalStateException 无 WHERE 条件且未显式调用 {@code allowFullTable()} 时抛出
     */
    public void requireWhereCondition(String operation) {
        if (conditions.isEmpty() && !fullTableAllowed) {
            throw new IllegalStateException("禁止无 WHERE 条件的全表" + operation + ": "
                    + entityClass.getSimpleName() + "，如需全表" + operation + "请显式调用 allowFullTable()");
        }
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
        requireWhereCondition("删除");
        List<Object> params = Wraps.list();
        var where = new StringBuilder();
        buildWhere(where, params);
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

    /**
     * NewInstance
     */
    @Override
    protected LambdaDeleteWrapper<T> newInstance() {
        return new LambdaDeleteWrapper<>(entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return LambdaColumnResolver.toColumnName(column);
    }

}
