package com.chua.common.support.lang.datasource.dialect;

import com.chua.common.support.lang.datasource.dialect.meta.PartitionMetadata;
import java.util.List;

/**
* 分区解析器，定义数据库分区管理的 DDL 操作。
*
* @author CH
* @since 2024/12/12
 */
public interface PartitionResolver {

    /** 创建Partition */
    default String createPartition(PartitionMetadata partitionMetadata) {
        throw new UnsupportedOperationException("当前数据库不支持创建分区");
    }

    /** DropPartition */
    default String dropPartition(String tableName, String partitionName) {
        throw new UnsupportedOperationException("当前数据库不支持删除分区");
    }

    /** TruncatePartition */
    default String truncatePartition(String tableName, String partitionName) {
        throw new UnsupportedOperationException("当前数据库不支持清空分区");
    }

    /** 查询Partitions */
    default String queryPartitions(String tableName, String schema) {
        throw new UnsupportedOperationException("当前数据库不支持查询分区");
    }

    /** 查询PartitionNames */
    default String queryPartitionNames(String tableName, String schema) {
        return queryPartitions(tableName, schema);
    }

    /** 是否存在Partition */
    default String existsPartition(String tableName, String partitionName, String schema) {
        throw new UnsupportedOperationException("当前数据库不支持查询分区存在性");
    }

    /**
    * ReorganizePartition
    * @param tableName tableName
    * @param sourcePartitionNames sourcePartitionNames
    * @param targetPartitions targetPartitions
    */
    default String reorganizePartition(String tableName, List<String> sourcePartitionNames,
                                       List<PartitionMetadata> targetPartitions) {
        throw new UnsupportedOperationException("当前数据库不支持重组分区");
    }
}
