package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullUnmarked;

/**
 * 外键定义，描述表之间的外键约束关系。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForeignKeyDef {

    /**
     * 外键名
     */
    private String name;

    /**
     * catalog 名称
     */
    private String catalog;

    /**
     * schema 名称
     */
    private String schema;

    /**
     * 当前表名（从表）
     */
    private String tableName;

    /**
     * 当前表列名
     */
    private String columnName;

    /**
     * 引用表名（主表）
     */
    private String refTableName;

    /**
     * 引用列名
     */
    private String refColumnName;

    /**
     * 删除规则（CASCADE / SET NULL / RESTRICT / NO ACTION）
     */
    private String onDelete;

    /**
     * 更新规则（CASCADE / SET NULL / RESTRICT / NO ACTION）
     */
    private String onUpdate;
}
