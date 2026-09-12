package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ProcedureDef;

/**
* 创建存储过程链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface ProcedureCreateBuilder {

    /**
    * 添加 IN 参数。
    *
    * @param name  参数名
    * @param type  参数类型（如 VARCHAR、INT）
    * @return this
     */
    ProcedureCreateBuilder in(String name, String type);

    /**
    * 添加 OUT 参数。
    *
    * @param name  参数名
    * @param type  参数类型
    * @return this
     */
    ProcedureCreateBuilder out(String name, String type);

    /**
    * 添加 INOUT 参数。
    *
    * @param name  参数名
    * @param type  参数类型
    * @return this
     */
    ProcedureCreateBuilder inout(String name, String type);

    /**
    * 添加参数（方向需要自己在 type 中指定，不推荐）。
    *
    * @param name     参数名
    * @param type     参数类型
    * @param direction 参数方向（IN / OUT / INOUT）
    * @return this
     */
    ProcedureCreateBuilder param(String name, String type, String direction);

    /**
    * 设置存储过程体 SQL。
    *
    * @param body 过程体
    * @return this
     */
    ProcedureCreateBuilder body(String body);

    /**
    * 设置过程语言（SQL / JAVA / PLPGSQL / PLSQL 等）。
    *
    * @param language 语言名
    * @return this
     */
    ProcedureCreateBuilder language(String language);

    /**
    * 设置安全类型（DEFINER / INVOKER，仅部分数据库支持）。
    *
    * @param securityType 安全类型
    * @return this
     */
    ProcedureCreateBuilder securityType(String securityType);

    /**
    * 设置注释。
    *
    * @param comment 注释内容
    * @return this
     */
    ProcedureCreateBuilder comment(String comment);

    /**
    * 使用 CREATE OR REPLACE 语义（如果数据库支持）。
    *
    * @return this
     */
    ProcedureCreateBuilder orReplace();

    /**
    * 执行建存储过程语句。
    *
    * @return 存储过程定义
     */
    ProcedureDef execute();
}
