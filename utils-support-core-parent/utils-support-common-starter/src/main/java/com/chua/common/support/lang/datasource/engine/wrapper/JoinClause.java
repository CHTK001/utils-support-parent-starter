package com.chua.common.support.lang.datasource.engine.wrapper;

/**
* JOIN 关联子句描述，记录一次表关联的类型、目标表、别名与 ON 条件。
* <p>
* 由 {@link LambdaQueryWrapper} 的 {@code innerJoin / leftJoin / rightJoin} 系列方法构建，
* 并随 {@link QuerySql#joins()} 传递给 SQL 引擎渲染为
* {@code INNER/LEFT/RIGHT JOIN 表 [别名] ON 条件} 子句。
* ON 条件为受控 SQL 片段（由调用方编写，通常引用两表的限定列名），不参与参数绑定。
* </p>
*
* @param joinType    JOIN 类型：INNER、LEFT 或 RIGHT
* @param table       关联表名
* @param alias       表别名，可为 null 或空串表示不使用别名
* @param onCondition ON 关联条件 SQL 片段，如 {@code "user.id = order.user_id"}
* @author CH
* @since 4.0.0.42
 * @return 结果值
 */
public record JoinClause(String joinType, String table, String alias, String onCondition) {

    /**
    * 渲染 FROM 子句中的表片段。
    * <p>有别名时输出 {@code 表名 别名}，否则仅输出表名。
    * 表名本身也允许携带别名（如 {@code "order o"}），此时 alias 参数应传 null。</p>
    *
    * @return 表片段，如 {@code "user u"}
    */
    public String renderTable() {
        if (alias == null || alias.isBlank()) {
            return table;
        }
        return table + " " + alias;
    }
}
