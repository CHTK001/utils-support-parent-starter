package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 更新 SQL 信息记录，包含构建更新语句所需的所有结构化数据。
 *
 * @param entityClass 实体类类型
 * @param setClause   SET 子句，可为 null
 * @param whereClause WHERE 条件片段，可为 null
 * @param params      参数列表，可为 null
 * @author CH
 * @since 2024/12/12
 */
public record UpdateSql<T>(
        Class<T> entityClass,
        String setClause,
        String whereClause,
        List<Object> params
) {

    /**
     * 规范构造器：实体类型必填，参数列表做防御性拷贝。
     *
     * <p>value class 前置条件——空值敌对，且集合组件必须深不可变。
     * {@code setClause} 与 {@code whereClause} 分别由 {@link #hasSet()}、
     * {@link #hasWhere()} 显式判空，允许为 null，不加约束。
     * 参数列表是 SQL 绑定值，既可能整体为 null，也可能含 null 绑定值，
     * 故保留 null 语义并采用可空安全写法。</p>
     *
     * @param entityClass 实体类类型，不允许为 null
     * @param setClause   SET 子句，可为 null
     * @param whereClause WHERE 条件片段，可为 null
     * @param params      参数列表，可为 null
     */
    public UpdateSql {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        params = params == null ? null : Collections.unmodifiableList(new ArrayList<>(params));
    }

    /**
     * 检查是否存在 SET 子句。
     *
     * @return 如果存在非空的 SET 子句则返回 true，否则返回 false
     */
    public boolean hasSet() {
        if (setClause == null || setClause.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * 检查是否存在 WHERE 子句。
     *
     * @return 如果存在非空的 WHERE 子句则返回 true，否则返回 false
     */
    public boolean hasWhere() {
        if (whereClause == null || whereClause.isEmpty()) {
            return false;
        }
        return true;
    }
}
