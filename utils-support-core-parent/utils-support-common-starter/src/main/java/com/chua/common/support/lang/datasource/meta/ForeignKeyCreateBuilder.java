package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;

/**
* 添加外键链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface ForeignKeyCreateBuilder {

    /**
    * 设置外键列名。
    *
    * @param columnName 当前表列名
    * @return this
     */
    ForeignKeyCreateBuilder column(String columnName);

    /**
    * 设置引用表和列。
    *
    * @param table  引用表名
    * @param column 引用列名
    * @return this
     */
    ForeignKeyCreateBuilder references(String table, String column);

    /**
    * 设置删除时的行为（CASCADE / SET NULL / RESTRICT / NO ACTION）。
    *
    * @param action 删除规则
    * @return this
     */
    ForeignKeyCreateBuilder onDelete(String action);

    /**
    * 设置更新时的行为（CASCADE / SET NULL / RESTRICT / NO ACTION）。
    *
    * @param action 更新规则
    * @return this
     */
    ForeignKeyCreateBuilder onUpdate(String action);

    /**
    * 执行外键添加语句。
    *
    * @return 外键定义
     */
    ForeignKeyDef execute();
}
