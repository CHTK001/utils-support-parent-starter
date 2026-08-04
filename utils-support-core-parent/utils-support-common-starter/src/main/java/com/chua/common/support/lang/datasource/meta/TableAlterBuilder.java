package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.table.TableDef;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 改表链式构建器。
 * <p>
 * 基于已有表结构进行变更，如新增列、删除列、修改列类型等。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 新增列
 * engine.meta().table("user")
 *     .alter()
 *     .addColumn("phone", "VARCHAR(20)").after("email")
 *     .execute();
 *
 * // 删除列
 * engine.meta().table("user")
 *     .alter()
 *     .dropColumn("old_field")
 *     .execute();
 *
 * // 修改列
 * engine.meta().table("user")
 *     .alter()
 *     .modifyColumn("name", "VARCHAR(150)").notNull()
 *     .execute();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface TableAlterBuilder {

    /**
     * 新增列。
     *
     * @param name 列名
     * @param type 数据库类型字符串
     * @return 列构建器（支持继续配置列属性）
     */
    AlterColumnBuilder addColumn(String name, String type);

    /**
     * 删除列。
     *
     * @param columnName 列名
     * @return this
     */
    TableAlterBuilder dropColumn(String columnName);

    /**
     * 修改列定义。
     *
     * @param columnName 列名
     * @param newType    新类型字符串
     * @return 列构建器（支持继续配置列属性）
     */
    AlterColumnBuilder modifyColumn(String columnName, String newType);

    /**
     * 添加联合主键。
     *
     * @param columns 主键列名
     * @return this
     */
    TableAlterBuilder addPrimaryKey(String... columns);

    /**
     * 删除主键。
     *
     * @return this
     */
    TableAlterBuilder dropPrimaryKey();

    /**
     * 添加索引。
     *
     * @param indexName 索引名
     * @return 索引构建器
     */
    AlterIndexBuilder addIndex(String indexName);

    /**
     * 删除索引。
     *
     * @param indexName 索引名
     * @return this
     */
    TableAlterBuilder dropIndex(String indexName);

    /**
     * 添加外键。
     *
     * @param fkName 外键名
     * @return 外键构建器
     */
    AlterForeignKeyBuilder addForeignKey(String fkName);

    /**
     * 删除外键。
     *
     * @param fkName 外键名
     * @return this
     */
    TableAlterBuilder dropForeignKey(String fkName);

    /**
     * 重命名表。
     *
     * @param newName 新表名
     * @return this
     */
    TableAlterBuilder renameTo(String newName);

    /**
     * 执行 ALTER TABLE 语句。
     *
     * @return 修改后的表定义
     */
    TableDef execute();
}
