package com.chua.common.support.lang.datasource.meta;


/**
* ALTER TABLE 中的外键构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface AlterForeignKeyBuilder {

    /**
    * 设置引用列。
    *
    * @param table  引用表名
    * @param column 引用列名
    * @return this
     */
    AlterForeignKeyBuilder references(String table, String column);

    /**
    * 设置删除规则（CASCADE / SET NULL / RESTRICT / NO ACTION）。
    *
    * @param action 删除规则
    * @return this
     */
    AlterForeignKeyBuilder onDelete(String action);

    /**
    * 设置更新规则（CASCADE / SET NULL / RESTRICT / NO ACTION）。
    *
    * @param action 更新规则
    * @return this
     */
    AlterForeignKeyBuilder onUpdate(String action);

    /**
    * 执行外键添加。
    *
    * @return this
     */
    TableAlterBuilder execute();
}
