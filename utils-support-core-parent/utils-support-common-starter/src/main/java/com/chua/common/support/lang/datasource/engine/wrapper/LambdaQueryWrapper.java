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
*   <li>SELECT 列指定 — {@link #select(SFunction)}、聚合函数投影 {@link #selectFunc}</li>
*   <li>WHERE 条件 — 继承自 {@link AbstractLambdaWrapper}</li>
*   <li>JOIN 关联 — {@link #innerJoin} / {@link #leftJoin} / {@link #rightJoin}</li>
*   <li>GROUP BY — {@link #groupBy(SFunction)}，分组过滤 {@link #having}</li>
*   <li>ORDER BY — 继承自 {@link AbstractLambdaWrapper}</li>
*   <li>分页下推 — {@link #limit(int)} / {@link #offset(int)}</li>
*   <li>SQL 构建 — {@link #buildSql()} 生成结构化的查询 SQL 信息</li>
* </ul>
* </p>
* <p>
* 该类的 {@code buildSql()} 方法将链式 API 构建的条件列表渲染为 SQL 片段，
* 生成 {@link QuerySql} 记录对象，包含 SELECT 列、WHERE 子句、参数列表、
* JOIN 关联、GROUP BY / HAVING、ORDER BY 列表与分页参数，
* 由 {@code Engine} 或 {@code SqlExecutor} 执行。
* </p>
*
* @param <T> 实体类型
* @author CH
* @since 2024/12/12
* @see AbstractLambdaWrapper
* @see LambdaUpdateWrapper
* @see LambdaDeleteWrapper
 */
public class LambdaQueryWrapper<T> extends AbstractLambdaWrapper<T, LambdaQueryWrapper<T>> {

    /** 查询列列表 */
    private final List<String> selectColumns = new ArrayList<>();
    /** 分组列名 */
    private String groupByColumn;
    /** 返回行数上限，0 表示不限制 */
    private int limit;
    /** 偏移行数，0 表示不偏移 */
    private int offset;
    /** JOIN 关联子句列表 */
    private final List<JoinClause> joins = new ArrayList<>();
    /** HAVING 条件片段（不含 HAVING 关键字），null 表示无分组过滤 */
    private String havingClause;
    /** HAVING 条件参数列表（与 ? 占位符顺序一致） */
    private final List<Object> havingParams = new ArrayList<>();

    /**
    * 创建 LambdaQueryWrapper 实例
    * @param entityClass entityClass
     */
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

    /**
    * 以字符串形式添加 GROUP BY 列，支持多列分组。
    *
    * @param columns 列名数组，至少一个
    * @return this
    */
    public LambdaQueryWrapper<T> groupBy(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("GROUP BY 列不能为空");
        }
        this.groupByColumn = String.join(", ", columns);
        return this;
    }

    // ==================== JOIN ====================

    /**
    * 添加 INNER JOIN 关联。
    *
    * @param table       关联表名，可携带别名（如 {@code "order o"}）
    * @param onCondition ON 关联条件 SQL 片段，如 {@code "user.id = order.user_id"}
    * @return this
    */
    public LambdaQueryWrapper<T> innerJoin(String table, String onCondition) {
        return addJoin("INNER", table, null, onCondition);
    }

    /**
    * 添加 INNER JOIN 关联（显式别名）。
    *
    * @param table       关联表名
    * @param alias       表别名
    * @param onCondition ON 关联条件 SQL 片段
    * @return this
    */
    public LambdaQueryWrapper<T> innerJoin(String table, String alias, String onCondition) {
        return addJoin("INNER", table, alias, onCondition);
    }

    /**
    * 添加 LEFT JOIN 关联。
    *
    * @param table       关联表名，可携带别名（如 {@code "order o"}）
    * @param onCondition ON 关联条件 SQL 片段
    * @return this
    */
    public LambdaQueryWrapper<T> leftJoin(String table, String onCondition) {
        return addJoin("LEFT", table, null, onCondition);
    }

    /**
    * 添加 LEFT JOIN 关联（显式别名）。
    *
    * @param table       关联表名
    * @param alias       表别名
    * @param onCondition ON 关联条件 SQL 片段
    * @return this
    */
    public LambdaQueryWrapper<T> leftJoin(String table, String alias, String onCondition) {
        return addJoin("LEFT", table, alias, onCondition);
    }

    /**
    * 添加 RIGHT JOIN 关联。
    *
    * @param table       关联表名，可携带别名（如 {@code "order o"}）
    * @param onCondition ON 关联条件 SQL 片段
    * @return this
    */
    public LambdaQueryWrapper<T> rightJoin(String table, String onCondition) {
        return addJoin("RIGHT", table, null, onCondition);
    }

    /**
    * 添加 RIGHT JOIN 关联（显式别名）。
    *
    * @param table       关联表名
    * @param alias       表别名
    * @param onCondition ON 关联条件 SQL 片段
    * @return this
    */
    public LambdaQueryWrapper<T> rightJoin(String table, String alias, String onCondition) {
        return addJoin("RIGHT", table, alias, onCondition);
    }

    /**
    * 追加一条 JOIN 关联子句（内部方法）。
    *
    * @param joinType    JOIN 类型：INNER、LEFT 或 RIGHT
    * @param table       关联表名
    * @param alias       表别名，可为 null
    * @param onCondition ON 关联条件 SQL 片段
    * @return this
    */
    private LambdaQueryWrapper<T> addJoin(String joinType, String table, String alias, String onCondition) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("JOIN 表名不能为空");
        }
        if (onCondition == null || onCondition.isBlank()) {
            throw new IllegalArgumentException("JOIN ON 条件不能为空");
        }
        joins.add(new JoinClause(joinType, table, alias, onCondition));
        return this;
    }

    // ==================== 聚合投影 ====================

    /**
    * 添加聚合函数投影列。
    * <p>渲染为 {@code 函数(列) AS 别名}，例如
    * {@code selectFunc("SUM", "amount", "totalAmount")} 生成 {@code SUM(amount) AS totalAmount}。
    * 列传 null 或空串时渲染为 {@code 函数(*)}。</p>
    *
    * @param function 聚合函数名，如 SUM、AVG、MAX、MIN、COUNT
    * @param column   聚合列名，null 或空串表示 {@code *}
    * @param alias    结果别名，null 或空串时不加 AS 子句
    * @return this
    */
    public LambdaQueryWrapper<T> selectFunc(String function, String column, String alias) {
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("聚合函数名不能为空");
        }
        String expr = column == null || column.isBlank()
                ? function + "(*)"
                : function + "(" + column + ")";
        if (alias != null && !alias.isBlank()) {
            expr = expr + " AS " + alias;
        }
        selectColumns.add(expr);
        return this;
    }

    /**
    * 添加 COUNT(*) 聚合投影列。
    *
    * @param alias 结果别名（如 {@code "cnt"}）
    * @return this
    */
    public LambdaQueryWrapper<T> selectCount(String alias) {
        return selectFunc("COUNT", "*", alias);
    }

    /**
    * 添加 SUM(列) 聚合投影列。
    *
    * @param column 聚合列名
    * @param alias  结果别名
    * @return this
    */
    public LambdaQueryWrapper<T> selectSum(String column, String alias) {
        return selectFunc("SUM", column, alias);
    }

    /**
    * 添加 AVG(列) 聚合投影列。
    *
    * @param column 聚合列名
    * @param alias  结果别名
    * @return this
    */
    public LambdaQueryWrapper<T> selectAvg(String column, String alias) {
        return selectFunc("AVG", column, alias);
    }

    /**
    * 添加 MAX(列) 聚合投影列。
    *
    * @param column 聚合列名
    * @param alias  结果别名
    * @return this
    */
    public LambdaQueryWrapper<T> selectMax(String column, String alias) {
        return selectFunc("MAX", column, alias);
    }

    /**
    * 添加 MIN(列) 聚合投影列。
    *
    * @param column 聚合列名
    * @param alias  结果别名
    * @return this
    */
    public LambdaQueryWrapper<T> selectMin(String column, String alias) {
        return selectFunc("MIN", column, alias);
    }

    // ==================== HAVING ====================

    /**
    * 添加 HAVING 分组过滤条件，须与 GROUP BY 配合使用。
    * <p>条件中的 {@code ?} 占位符由 params 按顺序绑定，
    * 参数绑定顺序在 WHERE 参数之后。</p>
    *
    * @param condition HAVING 条件片段，如 {@code "SUM(amount) > ?"}
    * @param params    条件参数列表
    * @return this
    */
    public LambdaQueryWrapper<T> having(String condition, Object... params) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("HAVING 条件不能为空");
        }
        this.havingClause = condition;
        if (params != null && params.length > 0) {
            havingParams.addAll(List.of(params));
        }
        return this;
    }

    // ==================== LIMIT / OFFSET ====================

    /**
    * 限制返回行数，下推到数据库分页语法（如 MySQL 的 LIMIT）。
    *
    * @param limit 返回行数上限，必须大于 0
    * @return this
    */
    public LambdaQueryWrapper<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        this.limit = limit;
        return this;
    }

    /**
    * 设置偏移行数，与 {@link #limit(int)} 配合实现数据库物理分页。
    *
    * @param offset 偏移行数，不能为负
    * @return this
    */
    public LambdaQueryWrapper<T> offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负");
        }
        this.offset = offset;
        return this;
    }

    /**
    * 获取返回行数上限。
    *
    * @return 上限，0 表示不限制
    */
    public int getLimit() {
        return limit;
    }

    /**
    * 获取偏移行数。
    *
    * @return 偏移行数
    */
    public int getOffset() {
        return offset;
    }

    // ==================== SQL 构建 ====================

    /**
    * 将当前链式 API 构建的条件渲染为结构化的查询 SQL 信息。
    * <p>
    * 返回的 {@link QuerySql} 记录了 SELECT 列、WHERE 子句、参数列表、JOIN 关联、
    * HAVING 条件等，可由 Engine 或外部处理器组合成完整的 SQL 语句并执行。
    * </p>
    *
    * @return 查询 SQL 信息
     */
    public QuerySql buildSql() {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        buildWhere(where, params);
        return new QuerySql(entityClass, selectColumns, where.toString(), params,
                groupByColumn, getOrderBys(), limit, offset,
                List.copyOf(joins), havingClause, List.copyOf(havingParams));
    }

    // ==================== 内部实现 ====================

    @Override
    /** NewInstance */
    protected LambdaQueryWrapper<T> newInstance() {
        return new LambdaQueryWrapper<>(entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        try {
            java.lang.reflect.Method writeReplace = column.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            java.lang.invoke.SerializedLambda lambda =
                    (java.lang.invoke.SerializedLambda) writeReplace.invoke(column);
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

    /**
    * 统计当前条件命中的总行数（不含分页参数）。
    * <p>由 {@code Engine} 实现类重写：SQL 引擎下推 {@code SELECT COUNT(*)} 执行，
    * 内存引擎回退为全量查询后计数。</p>
    *
    * @return 总行数
     */
    public long count() {
        throw new UnsupportedOperationException("count() 需由引擎实现类重写");
    }

}