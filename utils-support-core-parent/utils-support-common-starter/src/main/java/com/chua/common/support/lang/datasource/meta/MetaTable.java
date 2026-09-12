package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.table.TableDef;

import java.util.List;

/**
* 表元数据操作接口。
* <p>
* 提供表的查询、创建、修改、删除、重命名等链式操作。
* 上下文（catalog / schema）通过链式调用设置，不修改全局状态。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 查询所有表
* List<TableDef> tables = engine.meta().table().list();
*
* // 查询单表结构
* TableDef user = engine.meta().table("user").get();
*
* // 链式建表
* TableDef created = engine.meta().table()
*     .create("user")
*     .column("id", "BIGINT").primaryKey().autoIncrement()
*     .column("name", "VARCHAR(100)").notNull().comment("用户名")
*     .column("email", "VARCHAR(200)")
*     .engine("InnoDB").charset("utf8mb4")
*     .execute();
*
* // 删表
* boolean dropped = engine.meta().table("old_table").drop();
*
* // 重命名
* boolean renamed = engine.meta().table("old_name").rename("new_name");
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaTable {

    /**
    * 设置 catalog（流式设置，不影响全局）。
    *
    * @param catalog catalog 名称
    * @return this
     */
    MetaTable catalog(String catalog);

    /**
    * 设置 schema（流式设置，不影响全局）。
    *
    * @param schema schema 名称
    * @return this
     */
    MetaTable schema(String schema);

    /**
    * 列出当前 catalog/schema 下的所有表。
    *
    * @return 表定义列表
     */
    List<TableDef> list();

    /**
    * 获取当前表的完整结构定义。
    * <p>如果已通过 {@link MetaData#table(String)} 指定表名，直接返回该表结构；</p>
    * <p>如果未指定表名，返回当前上下文对应的表结构。</p>
    *
    * @return 表定义
     */
    TableDef get();

    /**
    * 创建表（链式构建器）。
    *
    * @param tableName 表名
    * @return 建表构建器
     */
    TableCreateBuilder create(String tableName);

    /**
    * 修改表（链式构建器）。
    * <p>基于已有表结构进行变更，如新增列、删除列、修改列等。</p>
    *
    * @return 改表构建器
     */
    TableAlterBuilder alter();

    /**
    * 删除表。
    *
    * @return true 删除成功
     */
    boolean drop();

    /**
    * 重命名表。
    *
    * @param newName 新表名
    * @return true 重命名成功
     */
    boolean rename(String newName);
}
