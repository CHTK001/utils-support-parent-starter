package com.chua.datasync.agent.support.model;

import java.io.Serializable;

/**
 * 数据偏移量记录。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SyncDataOffset implements Serializable {

    private static final long serialVersionUID = 4_0_0_42L;

    private final String sourceId;
    private final Object offsetValue;
    private final long timestamp;
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