package com.chua.common.support.lang.datasource.dialect.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
* 分区元数据，描述表的分区信息。
*
* <p>用于方言的分区 DDL 生成；支持范围分区、列表分区、哈希分区等多种类型，
* 可通过 {@link PartitionType} 指定分区策略并配置对应列、值与子分区。</p>
*
* @author CH
* @since 2024/12/12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class PartitionMetadata {

    /**
    * 分区名称
     */
    private String name;

    /**
    * 所属表名
     */
    private String tableName;

    /**
    * 分区类型
     */
    private PartitionType type;

    /**
    * 单列分区所对应的列名（与 {@link #columns} 二选一）
     */
    private String column;

    /**
    * 多列分区所对应的列名集合（与 {@link #column} 二选一）
     */
    private List<String> columns;

    /**
    * 分区界定值（与 {@link #values} 二选一）
     */
    private String value;

    /**
    * 分区界定值集合（与 {@link #value} 二选一）
     */
    private List<String> values;

    /**
    * 是否为 MAXVALUE 分区（用于 RANGE 分区的最后一档）
     */
    private boolean maxValue;

    /**
    * 分区数量（HASH / KEY 等分区使用）
     */
    private int partitions;

    /**
    * 分区表达式（自定义分区策略时使用）
     */
    private String expression;

    /**
    * 子分区类型（复合分区时使用）
     */
    private PartitionType subPartitionType;

    /**
    * 子分区列名（复合分区时使用）
     */
    private String subPartitionColumn;

    /**
    * 子分区数量（复合分区时使用）
     */
    private int subPartitions;

    /**
    * 分区备注
     */
    private String comment;

    /**
    * 分区类型枚举。
    *
    * <p>枚举值同时保存 SQL 关键字字符串，用于方言层 DDL 生成时的反向解析。</p>
     */
    public enum PartitionType {

        /**
        * 范围分区：基于列值范围切分
         */
        RANGE("RANGE"),

        /**
        * 多列范围分区
         */
        RANGE_COLUMNS("RANGE COLUMNS"),

        /**
        * 列表分区：基于列值离散集合切分
         */
        LIST("LIST"),

        /**
        * 多列列表分区
         */
        LIST_COLUMNS("LIST COLUMNS"),

        /**
        * 哈希分区
         */
        HASH("HASH"),

        /**
        * 线性哈希分区
         */
        LINEAR_HASH("LINEAR HASH"),

        /**
        * 键分区（MySQL 内部哈希算法）
         */
        KEY("KEY"),

        /**
        * 线性键分区
         */
        LINEAR_KEY("LINEAR KEY");

        /**
        * SQL DDL 中对应的关键字字符串
         */
        private final String keyword;

        /**
        * 构造分区类型枚举。
        *
        * @param keyword SQL DDL 中对应的关键字字符串
         */
        PartitionType(String keyword) {
            this.keyword = keyword;
        }

        /**
        * 获取 SQL DDL 中对应的关键字字符串。
        *
        * @return SQL 关键字
         */
        public String getKeyword() {
            return keyword;
        }

        /**
        * 根据字符串解析为 {@link PartitionType}。
        *
        * <p>解析规则：先比对 SQL 关键字（如 {@code "RANGE COLUMNS"}），再比对枚举名（如 {@code "RANGE_COLUMNS"}）。
        * 大小写不敏感，前后空格忽略。</p>
        *
        * @param type 类型字符串，允许为 null 或空
        * @return 解析得到的 {@link PartitionType}，未匹配时返回 {@code null}
         */
        public static PartitionType fromString(String type) {
            if (type == null || type.isBlank()) {
                return null;
            }
            String upperType = type.toUpperCase().trim();
            for (PartitionType pt : values()) {
                if (pt.keyword.equals(upperType) || pt.name().equals(upperType)) {
                    return pt;
                }
            }
            return null;
        }
    }

    /**
    * 构造 RANGE 分区元数据。
    *
    * @param name      分区名称
    * @param tableName 表名
    * @param column    分区列
    * @param value     分区界定值
    * @return RANGE 分区元数据
     */
    public static PartitionMetadata rangePartition(String name, String tableName, String column, String value) {
        return builder().name(name).tableName(tableName).type(PartitionType.RANGE).column(column).value(value).build();
    }

    /**
    * 构造 HASH 分区元数据。
    *
    * @param tableName  表名
    * @param column     分区列
    * @param partitions 分区数量
    * @return HASH 分区元数据
     */
    public static PartitionMetadata hashPartition(String tableName, String column, int partitions) {
        return builder().tableName(tableName).type(PartitionType.HASH).column(column).partitions(partitions).build();
    }
}
