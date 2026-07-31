package com.chua.datasource.support.ddl;

import com.chua.common.support.lang.datasource.table.TableDef;

import java.util.List;

/**
 * DDL / DSL 管理器 SPI 接口。
 * <p>
 * 提供表结构读取与 DDL 语句生成能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DslManager {

    /**
     * 获取表定义。
     *
     * @param catalogName catalog 名称
     * @param schemaName  schema 名称
     * @param tableName   表名
     * @return 表定义，不存在返回 null
     */
    TableDef getTable(String catalogName, String schemaName, String tableName);

    /**
     * 生成建表 DDL。
     *
     * @param catalogName catalog 名称
     * @param schemaName  schema 名称
     * @param tableName   表名
     * @return CREATE TABLE SQL
     */
    String createTableDDL(String catalogName, String schemaName, String tableName);

    /**
     * 生成重命名表 DDL。
     *
     * @param schemaName   schema 名称
     * @param oldTableName 原表名
     * @param newTableName 新表名
     * @return RENAME TABLE SQL
     */
    String renameTable(String schemaName, String oldTableName, String newTableName);

    /**
     * 生成复制表结构 DDL。
     *
     * @param schemaName      schema 名称
     * @param sourceTableName 源表名
     * @param targetTableName 目标表名
     * @return 复制结构 SQL
     */
    String copyTableStructure(String schemaName, String sourceTableName, String targetTableName);

    /**
     * 列出表定义。
     *
     * @param catalogName catalog 名称
     * @param schemaName  schema 名称
     * @return 表定义列表
     */
    List<TableDef> listTables(String catalogName, String schemaName);

    /**
     * 管理器类型标识。
     *
     * @return 类型名称
     */
    String type();
}
