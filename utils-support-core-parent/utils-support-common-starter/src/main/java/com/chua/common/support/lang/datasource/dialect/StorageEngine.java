package com.chua.common.support.lang.datasource.dialect;


/**
 * 数据库存储引擎枚举。
 * <p>主要用于 MySQL 等支持多种存储引擎的数据库。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
public enum StorageEngine {
    INNODB,
    MYISAM,
    MEMORY,
    CSV,
    ARCHIVE,
    DEFAULT
}
