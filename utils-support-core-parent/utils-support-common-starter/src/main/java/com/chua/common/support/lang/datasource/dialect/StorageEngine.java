package com.chua.common.support.lang.datasource.dialect;


/**
 * 数据库存储引擎枚举。
 * <p>主要用于 MySQL 等支持多种存储引擎的数据库。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
public enum StorageEngine {
    /** InnoDB 引擎，支持事务与行级锁 */
    INNODB,
    /** MyISAM 引擎，不支持事务 */
    MYISAM,
    /** MEMORY 内存表引擎 */
    MEMORY,
    /** CSV 引擎，以文本文件存储 */
    CSV,
    /** ARCHIVE 归档引擎，仅支持插入与查询 */
    ARCHIVE,
    /** 使用数据库默认存储引擎 */
    DEFAULT
}
