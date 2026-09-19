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
public class LambdaUpdateWrapper<T> extends AbstractLambdaWrapper<T, LambdaUpdateWrapper<T>> {

    /** SET 值映射：列名 → 新值 */
    private final Map<String, Object> setValues = new LinkedHashMap<>();

    /**
     * 创建 LambdaUpdateWrapper 实例
     * @param entityClass entityClass
     */
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
        setValues.put(checkIdentifier(column), value);
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
        if (setValues.isEmpty()) {
            throw new IllegalStateException("UPDATE 至少需要一个 SET 列: "
                    + entityClass.getSimpleName());
        }
        if (conditions.isEmpty() && !fullTableAllowed) {
            throw new IllegalStateException("禁止无 WHERE 条件的全表更新: "
                    + entityClass.getSimpleName() + "，如需全表更新请显式调用 allowFullTable()");
        }
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
     * <p>包装器层没有实体主键元数据（无 {@code @TableId} 语义），无法判定
     * INSERT 还是 UPDATE 的匹配条件；此前实现忽略入参直接执行空更新，
     * 属无效操作，现改为显式拒绝。</p>
     *
     * @param entity 实体实例
     * @return 受影响行数
     * @throws UnsupportedOperationException 恒定抛出，请改用
     *         {@code set(...).eq(主键列, 值).update()} 显式表达更新条件
     */
    public int saveOrUpdate(T entity) {
        throw new UnsupportedOperationException(
                "saveOrUpdate 未实现：更新包装器不携带主键元数据，请显式使用 set(...).eq(主键, 值).update()");
    }

    @Override
    /** NewInstance */
    protected LambdaUpdateWrapper<T> newInstance() {
        return new LambdaUpdateWrapper<>(entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        try {
            java.lang.reflect.Method writeReplace = column.getClass().getDeclaredMethod("writeReplace"); // [P3C 1.10 豁免] 序列化 writeReplace 为 lambda 私有方法，需精确方法句柄，无法用 ReflectUtils 替代
            // [P3C 1.10 豁免] 序列化 writeReplace 需精确方法句柄（SerializedLambda），无法用 ReflectUtils 替代
            writeReplace.setAccessible(true);
            java.lang.invoke.SerializedLambda lambda =
                    (java.lang.invoke.SerializedLambda) writeReplace.invoke(column); // [P3C 1.10 豁免] 同上：writeReplace 为 lambda 私有方法，ReflectUtils 公共 LOOKUP 无法访问
            String methodName = lambda.getImplMethodName();
            String field = methodName.startsWith("is") ? methodName.substring(2)
                    : methodName.startsWith("get") ? methodName.substring(3) : methodName;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < field.length(); i++) {
                char ch = field.charAt(i);
                if (Character.isUpperCase(ch)) {
                    if (i > 0) {
                        sb.append('_');
                    }
                    sb.append(Character.toLowerCase(ch));
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Lambda 列: " + column, e);
        }
    }

}
