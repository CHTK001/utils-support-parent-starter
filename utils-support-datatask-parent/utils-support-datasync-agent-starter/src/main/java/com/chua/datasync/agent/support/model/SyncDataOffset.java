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
    /** Serial版本UID */
    private static final long serialVersionUID = 4_0_0_42L;

    /** 数据源标识 */
    /** 来源ID */
    private final String sourceId;
    /** 偏移量值 */
    /** 偏移值 */
    private final Object offsetValue;
    /** 时间戳 */
    /** 时间戳 */
    private final long timestamp;
    /** 映射标识 */
    /** MappingID */
    private final String mappingId;

    public SyncDataOffset(String sourceId, Object offsetValue, long timestamp, String mappingId) {
        this.sourceId = sourceId;
        this.offsetValue = offsetValue;
        this.timestamp = timestamp;
        this.mappingId = mappingId;
    }

    public String sourceId() {
        return sourceId;
    }

    public Object offsetValue() {
        return offsetValue;
    }

    public long timestamp() {
        return timestamp;
    }

    public String mappingId() {
        return mappingId;
    }

    @Override
    public String toString() {
        return "SyncDataOffset{sourceId=" + sourceId + ", offset=" + offsetValue + ", ts=" + timestamp + ", mapping=" + mappingId + "}";
    }
}