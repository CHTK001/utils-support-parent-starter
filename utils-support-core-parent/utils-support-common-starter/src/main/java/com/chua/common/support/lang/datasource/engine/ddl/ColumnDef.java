package com.chua.common.support.lang.datasource.engine.ddl;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * DDL 列定义，描述建表语句中的单个列信息。
 *
 * <p>由 {@link DdlProvider#createTable(String, java.util.List)} 消费，
 * 各方言实现据此生成对应的列声明片段。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class ColumnDef {

    /**
     * 列名。
     */
    private String name;

    /**
     * 列类型（如 "VARCHAR(64)"、"INT"、"DATETIME"）。
     */
    private String type;

    /**
     * 是否允许为空。
     */
    private boolean nullable = true;

    /**
     * 是否为主键。
     */
    private boolean primaryKey = false;

    /**
     * 是否自增。
     */
    private boolean autoIncrement = false;

    /**
     * 默认值表达式（无默认值为 null）。
     */
    private String defaultValue;

    /**
     * 列备注（无备注为 null）。
     */
    private String comment;
}
