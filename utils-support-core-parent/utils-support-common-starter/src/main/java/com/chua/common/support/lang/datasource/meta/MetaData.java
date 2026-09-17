package com.chua.common.support.lang.datasource.meta;


/**
* 元数据统一操作入口。
* <p>
* 通过 {@link com.chua.common.support.lang.datasource.engine.Engine#meta()} 获取，
* 提供表、视图、索引、触发器、存储过程、外键、搜索引擎索引的统一 CRUD 接口。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 获取表定义
* TableDef user = engine.meta().table("user").get();
*
* // 链式建表
* TableDef created = engine.meta().table()
*     .create("user")
*     .column("id", "BIGINT").primaryKey().autoIncrement()
*     .column("name", "VARCHAR(100)").notNull()
*     .execute();
*
* // 创建索引
* engine.meta().index().onTable("user")
*     .create("idx_name")
*     .column("name")
*     .type("BTREE")
*     .execute();
*
* // 创建视图
* ViewDef v = engine.meta().view("v_user")
*     .definition("select id, name from user where status = 1")
*     .orReplace()
*     .execute();
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaData {

    /**
    * 获取表元数据操作入口。
    *
    * @return 表操作接口
    */
    MetaTable table();

    /**
    * 获取指定表的元数据操作入口。
    *
    * @param tableName 表名
    * @return 表操作接口
    */
    MetaTable table(String tableName);

    /**
    * 获取视图元数据操作入口。
    *
    * @return 视图操作接口
    */
    MetaView view();

    /**
    * 获取指定视图的元数据操作入口。
    *
    * @param viewName 视图名
    * @return 视图操作接口
    */
    MetaView view(String viewName);

    /**
    * 获取索引入口。
    *
    * @return 索引操作接口
    */
    MetaIndex index();

    /**
    * 获取指定索引入口。
    *
    * @param indexName 索引名
    * @return 索引操作接口
    */
    MetaIndex index(String indexName);

    /**
    * 获取触发器操作入口。
    *
    * @return 触发器操作接口
    */
    MetaTrigger trigger();

    /**
    * 获取指定触发器操作入口。
    *
    * @param triggerName 触发器名
    * @return 触发器操作接口
    */
    MetaTrigger trigger(String triggerName);

    /**
    * 获取存储过程操作入口。
    *
    * @return 存储过程操作接口
    */
    MetaProcedure procedure();

    /**
    * 获取指定存储过程操作入口。
    *
    * @param procedureName 存储过程名
    * @return 存储过程操作接口
    */
    MetaProcedure procedure(String procedureName);

    /**
    * 获取外键操作入口。
    *
    * @return 外键操作接口
    */
    MetaForeignKey fk();

    /**
    * 获取指定外键操作入口。
    *
    * @param fkName 外键名
    * @return 外键操作接口
    */
    MetaForeignKey fk(String fkName);

    /**
    * 获取用户操作入口。
    *
    * @return 用户操作接口
    */
    MetaUser user();

    /**
    * 获取权限操作入口。
    *
    * @return 权限操作接口
    */
    MetaPermission permission();

    /**
    * 获取搜索引擎索引操作入口。
    *
    * @return 搜索引擎索引操作接口
    */
    MetaSearch search();

    /**
    * 获取指定搜索引擎索引操作入口。
    *
    * @param indexName 索引名
    * @return 搜索引擎索引操作接口
    */
    MetaSearch search(String indexName);
}
