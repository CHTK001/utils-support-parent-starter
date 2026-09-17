package com.chua.common.support.lang.datasource.dialect.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import lombok.Data;
import lombok.experimental.Accessors;
import java.util.List;

/**
* 表元数据，描述一个数据库表的完整结构信息。
* <p>用于方言的 DDL 生成，如 CREATE TABLE、分区定义等操作。</p>
*
* @author CH
* @since 2024/12/12
 */
@Data
@Accessors(chain = true)
public class TableMetadata {

    /** 表名 */
    /**
    * 名称
    */
    private String name;
    /** 所属 Schema */
    /**
    * Schema 名
    */
    private String schema;
    /** 列元数据列表 */
    private List<ColumnMetadata> columns;
    /** 索引元数据列表 */
    private List<IndexMetadata> indexes;
    /** 表类型 */
    /**
    * 类型
    */
    private String type = "TABLE";
    /** 表注释 */
    private String comment;
    /** 分区类型（RANGE / LIST / HASH / KEY） */
    private String partitionType;
    /** 分区列名 */
    private String partitionColumn;
    /** 分区自定义定义 */
    private String partitionDefinition;
    /** 存储引擎 */
    private String engine = "InnoDB";

    /** 是否配置了分区 */
    public boolean hasPartitions() {
        return (partitionType != null && !partitionType.isEmpty()
                && partitionColumn != null && !partitionColumn.isEmpty())
                || (partitionDefinition != null && !partitionDefinition.isEmpty());
    }

    /** 获取分区列元数据 */
    public ColumnMetadata getPartitionColumn() {
        if (partitionColumn == null || partitionColumn.isEmpty() || columns == null) { return null; }
        return columns.stream()
                .filter(col -> partitionColumn.equals(col.getColumnName()))
                .findFirst().orElse(null);
    }

    /** 格式化分区 SQL */
    public String formatPartitionSql(Dialect dialect) {
        if (!hasPartitions()) {
            return "";
        }
        if (partitionDefinition != null && !partitionDefinition.isEmpty()) {
            return partitionDefinition;
        }
        return dialect.formatPartitionSql(this);
    }

    /** 主键列 */
    public ColumnMetadata getPrimaryKeyColumn() {
        if (columns == null) {
            return null;
        }
        return columns.stream().filter(ColumnMetadata::isPrimaryKey).findFirst().orElse(null);
    }

    /** 是否有主键 */
    public boolean hasPrimaryKey() {
        return getPrimaryKeyColumn() != null;
    }
}
