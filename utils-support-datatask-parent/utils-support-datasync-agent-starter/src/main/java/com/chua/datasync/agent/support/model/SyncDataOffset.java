package com.chua.datasync.agent.support.model;

import java.io.Serializable;

/**
 * 数据偏移量记录。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SyncDataOffset implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 4_0_0_42L;

    /** 数据源标识 */
    private final String sourceId;
    /** 偏移量值 */
    private final Object offsetValue;
    /** 时间戳 */
    private final long timestamp;
    /** 映射标识 */
    private final String mappingId;

    /**
     * 创建 SyncDataOffset 实例
     * @param sourceId sourceId
     * @param Object Object
     * @param long long
     * @param String String
     */
    public SyncDataOffset(String sourceId, Object offsetValue, long timestamp, String mappingId) {
        this.sourceId = sourceId;
        this.offsetValue = offsetValue;
        this.timestamp = timestamp;
        this.mappingId = mappingId;
    }

    /** SourceId */
    public String sourceId() {
        return sourceId;
    }

    /** OffsetValue */
    public Object offsetValue() {
        return offsetValue;
    }

    /** Timestamp */
    public long timestamp() {
        return timestamp;
    }

    /** MappingId */
    public String mappingId() {
        return mappingId;
    }

    @Override
    /** ToString */
    public String toString() {
        return "SyncDataOffset{sourceId=" + sourceId + ", offset=" + offsetValue + ", ts=" + timestamp + ", mapping=" + mappingId + "}";
    }
}