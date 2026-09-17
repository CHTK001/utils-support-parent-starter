package com.chua.common.support.lang.datasource.table;

import lombok.Data;
import lombok.experimental.Accessors;

/**
* 列定义，描述数据库表中的一个字段。
* <p>
* 包含字段名称、数据类型、是否可为空、是否主键、是否自增、默认值、
* 注释、列顺序、字符集、排序规则等完整的列属性。配合 {@link TableDef} 和 {@link DdlBuilder} 使用，
* 用于生成 DDL 语句。
* </p>
* <p>
* 属性说明：
* <ul>
*   <li>{@code name} — 列名</li>
*   <li>{@code type} — 数据库类型字符串（如 VARCHAR(255)、BIGINT、TEXT）</li>
*   <li>{@code nullable} — 是否可为空（默认 true）</li>
*   <li>{@code primaryKey} — 是否主键</li>
*   <li>{@code autoIncrement} — 是否自增</li>
*   <li>{@code unsigned} — 是否无符号（数值类型）</li>
*   <li>{@code defaultValue} — 默认值表达式</li>
*   <li>{@code comment} — 列注释</li>
*   <li>{@code length} — 类型长度（如 VARCHAR(255) 的 255）</li>
*   <li>{@code precision} — 数字精度</li>
*   <li>{@code scale} — 小数位数</li>
*   <li>{@code after} — 在哪个列之后添加（ALTER TABLE 使用）</li>
*   <li>{@code first} — 是否添加到第一列（ALTER TABLE 使用）</li>
*   <li>{@code ordinalPosition} — 列顺序（JDBC 标准字段）</li>
*   <li>{@code charset} — 列字符集</li>
*   <li>{@code collation} — 列排序规则</li>
* </ul>
* </p>
*
* @author CH
* @since 2024/12/12
 */
@Data
@Accessors(chain = true)
public class ColumnDef {

    /**
    * 列名
    */
    private String name;

    /**
    * 数据库类型字符串（如 VARCHAR(255)、BIGINT、TEXT）
    */
    private String type;

    /**
    * 是否可为空（默认 true）
    */
    private boolean nullable = true;

    /**
    * 是否主键
    */
    private boolean primaryKey;

    /**
    * 是否自增
    */
    private boolean autoIncrement;

    /**
    * 是否无符号（数值类型）
    */
    private boolean unsigned;

    /**
    * 默认值表达式
    */
    private String defaultValue;

    /**
    * 列注释
    */
    private String comment;

    /**
    * 类型长度（如 VARCHAR(255) 的 255）
    */
    private Long length;

    /**
    * 数字精度
    */
    private Integer precision;

    /**
    * 小数位数
    */
    private Integer scale;

    /**
    * 在哪个列之后添加（ALTER TABLE 使用）
    */
    private String after;

    /**
    * 是否添加到第一列（ALTER TABLE 使用）
    */
    private boolean first;

    /**
    * 列顺序（JDBC 标准字段）
    */
    private Integer ordinalPosition;

    /**
    * 列字符集
    */
    private String charset;

    /**
    * 列排序规则
    */
    private String collation;
}
