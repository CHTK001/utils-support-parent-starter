package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发器定义，描述数据库中的一个触发器。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerDef {

    /**
     * 触发器名
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
     * 所属表名
     */
    private String tableName;

    /**
     * 触发时机（BEFORE / AFTER / INSTEAD OF）
     */
    private String timing;

    /**
     * 触发事件（INSERT / UPDATE / DELETE）
     */
    private String event;

    /**
     * 是否逐行触发
     */
    private boolean forEachRow;

    /**
     * 触发器体 SQL
     */
    private String body;

    /**
     * 触发器状态（ENABLED / DISABLED）
     */
    private String status;
}
