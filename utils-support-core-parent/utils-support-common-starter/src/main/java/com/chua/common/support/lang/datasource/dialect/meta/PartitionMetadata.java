package com.chua.common.support.lang.datasource.dialect.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.NoArgsConstructor;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 分区元数据，描述表的分区信息。
 * <p>用于方言的分区 DDL 生成。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@SuppressWarnings("NullAway")
@NullUnmarked
public class PartitionMetadata {

    /**
     * 名称
     */
    private String name;
    private String tableName;
    /**
     * 类型
     */
    private PartitionType type;
    /**
     * 列名
     */
    private String column;
    private List<String> columns;
    /**
     * 值
     */
    private String value;
    private List<String> values;
    private boolean maxValue;
    private int partitions;
    private String expression;
    private PartitionType subPartitionType;
    private String subPartitionColumn;
    private int subPartitions;
    private String comment;

    public enum PartitionType {
        RANGE("RANGE"),
        RANGE_COLUMNS("RANGE COLUMNS"),
        LIST("LIST"),
        LIST_COLUMNS("LIST COLUMNS"),
        HASH("HASH"),
        LINEAR_HASH("LINEAR HASH"),
        KEY("KEY"),
        LINEAR_KEY("LINEAR KEY");

        private final String keyword;

        PartitionType(String keyword) {
            this.keyword = keyword;
        }
        public String getKeyword() { return keyword; }

        public static PartitionType fromString(String type) {
            if (type == null || type.isBlank()) { return null; }
            String upperType = type.toUpperCase().trim();
            for (PartitionType pt : values()) {
                if (pt.keyword.equals(upperType) || pt.name().equals(upperType)) return pt;
            }
            return null;
        }
    }

    public static PartitionMetadata rangePartition(String name, String tableName, String column, String value) {
        return builder().name(name).tableName(tableName).type(PartitionType.RANGE).column(column).value(value).build();
    }

    public static PartitionMetadata hashPartition(String tableName, String column, int partitions) {
        return builder().tableName(tableName).type(PartitionType.HASH).column(column).partitions(partitions).build();
    }
}