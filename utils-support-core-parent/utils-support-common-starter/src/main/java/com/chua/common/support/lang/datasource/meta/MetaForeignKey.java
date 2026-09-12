package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;

/**
* 外键元数据操作接口。
* <p>
* 提供外键的查询、添加、删除等链式操作。
* 通过 {@link #onTable(String)} 指定所属表名后再执行操作。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 列出表的所有外键
* List<ForeignKeyDef> fks = engine.meta().fk().onTable("order").list();
*
* // 添加外键
* engine.meta().fk().onTable("order")
*     .add("fk_order_user_id")
*     .references("user", "id")
*     .onDelete("CASCADE")
*     .execute();
*
* // 删除外键
* boolean dropped = engine.meta().fk().onTable("order").drop("fk_order_user_id");
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaForeignKey {

    /**
    * 指定所属表名。
    *
    * @param tableName 表名
    * @return this
     */
    MetaForeignKey onTable(String tableName);

    /**
    * 列出当前表的所有外键。
    *
    * @return 外键定义列表
     */
    java.util.List<ForeignKeyDef> list();

    /**
    * 获取指定外键的定义。
    *
    * @param fkName 外键名
    * @return 外键定义
     */
    ForeignKeyDef get(String fkName);

    /**
    * 添加外键（链式构建器）。
    *
    * @param fkName 外键名
    * @return 添加外键构建器
     */
    ForeignKeyCreateBuilder add(String fkName);

    /**
    * 删除外键。
    *
    * @param fkName 外键名
    * @return true 删除成功
     */
    boolean drop(String fkName);
}
