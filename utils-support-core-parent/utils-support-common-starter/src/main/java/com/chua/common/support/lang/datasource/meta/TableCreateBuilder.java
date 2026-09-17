package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.table.TableDef;

/**
* 建表链式构建器。
* <p>
* 通过流式 API 构建 CREATE TABLE 语句并执行，返回生成的 {@link TableDef}。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* TableDef created = engine.meta().table()
*     .create("user")
*     .column("id", "BIGINT").primaryKey().autoIncrement()
*     .column("name", "VARCHAR(100)").notNull().comment("用户名")
*     .column("email", "VARCHAR(200)")
*     .engine("InnoDB").charset("utf8mb4")
*     .execute();
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface TableCreateBuilder {

    /**
    * 添加列定义。
    *
    * @param name 列名
    * @param type 数据库类型字符串（如 VARCHAR(100)、BIGINT）
    * @return this
    */
    TableCreateBuilder column(String name, String type);

    /**
    * 将最后添加的列设为 NOT NULL。
    *
    * @return this
    */
    TableCreateBuilder notNull();

    /**
    * 将最后添加的列设为主键。
    * <p>同时自动设置该列为 NOT NULL。</p>
    *
    * @return this
    */
    TableCreateBuilder primaryKey();

    /**
    * 将最后添加的列设为自增。
    *
    * @return this
    */
    TableCreateBuilder autoIncrement();

    /**
    * 将最后添加的列设为无符号（仅数值类型）。
    *
    * @return this
    */
    TableCreateBuilder unsigned();

    /**
    * 设置最后添加的列的默认值。
    *
    * @param val 默认值表达式
    * @return this
    */
    TableCreateBuilder defaultValue(String val);

    /**
    * 设置最后添加的列的注释。
    *
    * @param val 列注释
    * @return this
    */
    TableCreateBuilder comment(String val);

    /**
    * 将最后添加的列排在指定列之后。
    *
    * @param columnName 前一列名
    * @return this
    */
    TableCreateBuilder after(String columnName);

    /**
    * 将最后添加的列设为第一列。
    *
    * @return this
    */
    TableCreateBuilder first();

    /**
    * 设置联合主键（指定多个列名）。
    *
    * @param columns 主键列名
    * @return this
    */
    TableCreateBuilder primaryKey(String... columns);

    /**
    * 设置表注释。
    *
    * @param comment 表注释
    * @return this
    */
    TableCreateBuilder commentTable(String comment);

    /**
    * 设置数据库引擎（如 InnoDB）。
    *
    * @param engine 引擎名
    * @return this
    */
    TableCreateBuilder engine(String engine);

    /**
    * 设置字符集（如 utf8mb4）。
    *
    * @param charset 字符集名
    * @return this
    */
    TableCreateBuilder charset(String charset);

    /**
    * 设置排序规则（如 utf8mb4_general_ci）。
    *
    * @param collate 排序规则名
    * @return this
    */
    TableCreateBuilder collate(String collate);

    /**
    * 执行建表语句。
    *
    * @return 建表后的表定义
    */
    TableDef execute();
}
