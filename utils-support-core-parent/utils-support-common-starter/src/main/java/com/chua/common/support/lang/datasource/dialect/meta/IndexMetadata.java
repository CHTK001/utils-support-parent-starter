package com.chua.common.support.lang.datasource.dialect.meta;

import lombok.Data;
import lombok.experimental.Accessors;
import java.util.List;

/**
 * 索引元数据，描述表上的一个索引信息。
 * <p>用于方言的索引 DDL 生成，如 CREATE INDEX、DROP INDEX 等操作。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Data
@Accessors(chain = true)
public class IndexMetadata {

    /** 索引名称 */
    private String name;
    /** 所属表名 */
    private String tableName;
    /** 索引类型（BTREE / HASH / FULLTEXT） */
    private String type;
    /** 是否唯一索引 */
    private boolean unique;
    /** 是否主键索引 */
    private boolean primary;
    /** 是否不可见 */
    private boolean invisible;
    /** 索引列名列表 */
    private List<String> columns;
    /** 单列索引的列名 */
    private String columnName;
    /** 索引位置 */
    private Integer position;
    /** 排序方向 */
    private String sortDirection;
    /** 索引注释 */
    private String comment;
}
