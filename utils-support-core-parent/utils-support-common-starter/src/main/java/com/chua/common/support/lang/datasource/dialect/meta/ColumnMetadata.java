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

    /**
     * 列名
    */
    private String columnName;
    /**
     * 所属表名
    */
    private String tableName;
    /**
     * JDBC 类型名称
    */
    private String jdbcType;
    /**
     * JDBC 类型代码
    */
    private int sqlType;
    /**
     * 字段长度
    */
    private int length;
    /**
     * 数字精度
    */
    private int precision;
    /**
     * 小数位数
    */
    private int scale;
    /**
     * 是否可为空
    */
    private boolean nullable = true;
    /**
     * 默认值
    */
    private String defaultValue;
    /**
     * 注释
    */
    private String comment;
    /**
     * 是否主键
    */
    private boolean primaryKey;
    /**
     * 是否自增
    */
    private boolean autoIncrement;
    /**
     * 字段位置
    */
    private int position;

    /**
     * 使用方言的引用符包裹列名
     * @param dialect 方法入参 dialect
     * @return 结果字符串
     */
    public String getQuotedName(Dialect dialect) {
        return dialect.quote(columnName);
    }
}
