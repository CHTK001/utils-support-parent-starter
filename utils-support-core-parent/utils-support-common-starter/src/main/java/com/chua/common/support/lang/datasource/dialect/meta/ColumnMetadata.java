package com.chua.common.support.lang.datasource.dialect.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 列元数据，描述表中的一个列（字段）信息。
 * <p>用于方言的 DDL 生成，如 CREATE TABLE、ALTER TABLE 等操作。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Data
@Accessors(chain = true)
public class ColumnMetadata {

    /** 列名 */
    /** 列名称 */
    private String columnName;
    /** 所属表名 */
    /** 表名称 */
    private String tableName;
    /** JDBC 类型名称 */
    /** JDBC类型 */
    private String jdbcType;
    /** JDBC 类型代码 */
    /** SQL类型 */
    private int sqlType;
    /** 字段长度 */
    /** 长度 */
    private int length;
    /** 数字精度 */
    /** Precision */
    private int precision;
    /** 小数位数 */
    /** 比例尺 */
    private int scale;
    /** 是否可为空 */
    /**
     * 是否允许为空
     */
    private boolean nullable = true;
    /** 默认值 */
    private String defaultValue;
    /** 注释 */
    /** Comment */
    private String comment;
    /** 是否主键 */
    /**
     * 主键字段名
     */
    private boolean primaryKey;
    /** 是否自增 */
    /** Autoincrement */
    private boolean autoIncrement;
    /** 字段位置 */
    /** 位置 */
    private int position;

    /** 使用方言的引用符包裹列名 */
    public String getQuotedName(Dialect dialect) {
        return dialect.quote(columnName);
    }
}