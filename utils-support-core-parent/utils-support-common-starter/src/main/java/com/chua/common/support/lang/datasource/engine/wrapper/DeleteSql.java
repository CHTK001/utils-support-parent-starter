package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 删除 SQL 信息记录，包含构建删除语句所需的所有结构化数据。
 *
 * @param entityClass 实体类类型
 * @param whereClause WHERE 条件片段，可为 null
 * @param params      WHERE 参数列表，可为 null
 * @author CH
 * @since 2024/12/12
 */
public record DeleteSql<T>(
        Class<T> entityClass,
        String whereClause,
        List<Object> params
) {

    /**
     * 规范构造器：实体类型必填，参数列表做防御性拷贝。
     *
     * <p>value class 前置条件——空值敌对，且集合组件必须深不可变。
     * {@code whereClause} 由 {@link #hasWhere()} 显式判空，允许为 null，不加约束。
     * 参数列表是 SQL 绑定值，既可能整体为 null，也可能含 null 绑定值
     * （如 {@code eq("name", null)}），故保留 null 语义并采用可空安全写法。</p>
     *
     * @param entityClass 实体类类型，不允许为 null
     * @param whereClause WHERE 条件片段，可为 null
     * @param params      WHERE 参数列表，可为 null
     */
    public DeleteSql {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        params = params == null ? null : Collections.unmodifiableList(new ArrayList<>(params));
    }

    /**
     * 判断是否存在有效的 WHERE 子句。
     *
     * @return 如果存在 WHERE 子句则返回 true，否则返回 false
     */
    public boolean hasWhere() {
        return whereClause != null && !whereClause.isEmpty();
    }
}
