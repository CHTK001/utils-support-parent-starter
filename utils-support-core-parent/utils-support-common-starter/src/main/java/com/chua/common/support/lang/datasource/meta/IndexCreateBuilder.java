package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;

/**
* 创建索引链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface IndexCreateBuilder {

    /**
    * 添加索引列（单列）。
    *
    * @param columnName 列名
    * @return this
    */
    IndexCreateBuilder column(String columnName);

    /**
    * 添加索引列（多列）。
    *
    * @param columnNames 列名列表
    * @return this
    */
    IndexCreateBuilder columns(String... columnNames);

    /**
    * 设置为唯一索引。
    *
    * @return this
    */
    IndexCreateBuilder unique();

    /**
    * 设置索引类型（BTREE / HASH / FULLTEXT / SPATIAL 等）。
    *
    * @param type 索引类型
    * @return this
    */
    IndexCreateBuilder type(String type);

    /**
    * 设置索引算法（仅 MySQL 支持，如 BTREE / HASH）。
    *
    * @param algorithm 算法名
    * @return this
    */
    IndexCreateBuilder using(String algorithm);

    /**
    * 设置索引注释（仅 MySQL 支持）。
    *
    * @param comment 注释内容
    * @return this
    */
    IndexCreateBuilder comment(String comment);

    /**
    * 设置索引可见性（INVISIBLE / VISIBLE，仅 MySQL 支持）。
    *
    * @param visible 是否可见
    * @return this
    */
    IndexCreateBuilder visible(boolean visible);

    /**
    * 执行建索引语句。
    *
    * @return 创建的索引定义
    */
    IndexMetadata execute();
}
