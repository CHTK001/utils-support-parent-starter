package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;

import java.util.List;

/**
* 索引入口接口。
* <p>
* 提供表索引的查询、创建、删除等操作。
* 通过 {@link #onTable(String)} 指定所属表名后再执行操作。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 列出表的所有索引
* List<IndexDef> indexes = engine.meta().index().onTable("user").list();
*
* // 创建索引
* engine.meta().index().onTable("user")
*     .create("idx_name")
*     .column("name")
*     .type("BTREE")
*     .execute();
*
* // 删除索引
* boolean dropped = engine.meta().index().onTable("user").drop("idx_name");
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaIndex {

    /**
    * 指定所属表名。
    *
    * @param tableName 表名
    * @return this
    */
    MetaIndex onTable(String tableName);

    /**
    * 列出当前表的所有索引。
    *
    * @return 索引定义列表
    */
    List<IndexMetadata> list();

    /**
    * 获取指定索引的定义。
    *
    * @param indexName 索引名
    * @return 索引定义
    */
    IndexMetadata get(String indexName);

    /**
    * 创建索引（链式构建器）。
    *
    * @param indexName 索引名
    * @return 创建索引构建器
    */
    IndexCreateBuilder create(String indexName);

    /**
    * 删除索引。
    *
    * @param indexName 索引名
    * @return true 删除成功
    */
    boolean drop(String indexName);
}
