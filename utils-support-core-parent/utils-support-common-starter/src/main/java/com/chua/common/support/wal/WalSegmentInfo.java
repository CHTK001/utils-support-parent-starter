package com.chua.common.support.wal;

import java.nio.file.Path;

/**
 * WAL 分片元信息。
 *
 * @param segmentNo   分片序号，从 1 开始
 * @param firstLsn    分片首条记录 LSN
 * @param lastLsn     分片末条记录 LSN
 * @param recordCount 分片记录数
 * @param path        分片文件路径
 * @param active      是否为当前活跃分片
 * @author CH
 * @since 4.0.0.42
*/
public record WalSegmentInfo(
    int segmentNo,
    long firstLsn,
    long lastLsn,
    int recordCount,
    Path path,
    boolean active
) {

    /**
    * 分片是否为空（无任何记录）。
    *
    * @return true=空
    */
    public boolean isEmpty() {
        return recordCount <= 0;
    }

    /**
    * 分片是否可删除（已 checkpoint 且非活跃）。
    *
    * @return true=可删除
    */
    public boolean isRemovable() {
        return !active && !isEmpty();
    }
}
