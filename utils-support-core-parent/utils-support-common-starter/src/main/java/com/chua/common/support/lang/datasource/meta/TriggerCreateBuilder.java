package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.TriggerDef;

/**
* 创建触发器链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface TriggerCreateBuilder {

    /**
    * 指定所属表名。
    *
    * @param tableName 表名
    * @return this
    */
    TriggerCreateBuilder onTable(String tableName);

    /**
    * 设置触发时机为 BEFORE（在事件之前执行）。
    *
    * @param event 触发事件（INSERT / UPDATE / DELETE）
    * @return this
    */
    TriggerCreateBuilder before(String event);

    /**
    * 设置触发时机为 AFTER（在事件之后执行）。
    *
    * @param event 触发事件（INSERT / UPDATE / DELETE）
    * @return this
    */
    TriggerCreateBuilder after(String event);

    /**
    * 设置触发时机为 INSTEAD OF（替换事件，仅视图支持）。
    *
    * @param event 触发事件（INSERT / UPDATE / DELETE）
    * @return this
    */
    TriggerCreateBuilder insteadOf(String event);

    /**
    * 设置为逐行触发（FOR EACH ROW）。
    *
    * @return this
    */
    TriggerCreateBuilder forEachRow();

    /**
    * 设置为语句级触发（FOR EACH STATEMENT，默认）。
    *
    * @return this
    */
    TriggerCreateBuilder forEachStatement();

    /**
    * 设置触发器体 SQL。
    *
    * @param body 触发器体
    * @return this
    */
    TriggerCreateBuilder body(String body);

    /**
    * 设置触发器为启用状态。
    *
    * @return this
    */
    TriggerCreateBuilder enable();

    /**
    * 设置触发器为禁用状态。
    *
    * @return this
    */
    TriggerCreateBuilder disable();

    /**
    * 设置触发器注释（仅部分数据库支持）。
    *
    * @param comment 注释内容
    * @return this
    */
    TriggerCreateBuilder comment(String comment);

    /**
    * 执行建触发器语句。
    *
    * @return 触发器定义
    */
    TriggerDef execute();
}
