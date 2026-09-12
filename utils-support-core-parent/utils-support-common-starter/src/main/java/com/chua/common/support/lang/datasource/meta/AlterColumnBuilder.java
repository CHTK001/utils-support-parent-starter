package com.chua.common.support.lang.datasource.meta;


/**
* ALTER TABLE 中的列构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface AlterColumnBuilder {

    /**
    * 将列设为 NOT NULL。
    *
    * @return this
     */
    AlterColumnBuilder notNull();

    /**
    * 设置默认值。
    *
    * @param val 默认值表达式
    * @return this
     */
    AlterColumnBuilder defaultValue(String val);

    /**
    * 设置列注释。
    *
    * @param comment 列注释
    * @return this
     */
    AlterColumnBuilder comment(String comment);

    /**
    * 设置列位置（AFTER）。
    *
    * @param columnName 前一列名
    * @return this
     */
    AlterColumnBuilder after(String columnName);

    /**
    * 将列设为第一列。
    *
    * @return this
     */
    AlterColumnBuilder first();

    /**
    * 执行列修改。
    *
    * @return this
     */
    TableAlterBuilder execute();
}
