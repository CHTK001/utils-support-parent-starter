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
    * 创建 同步数据偏移量 实例
    * @param sourceId 源标识
    * @param offsetValue 对象
    * @param timestamp long
    * @param sourceId 字符串
    * @param offsetValue 偏移量值
    * @param timestamp 时间戳
    * @param mappingId mappingid
     */
    public SyncDataOffset(String sourceId, Object offsetValue, long timestamp, String mappingId) {
        this.sourceId = sourceId;
        this.offsetValue = offsetValue;
        this.timestamp = timestamp;
        this.mappingId = mappingId;
    }

    /**
    * 源id
    *
    * @return 源id的结果
     */
    public String sourceId() {
        return sourceId;
    }

    /**
    * 偏移量值
    *
    * @return 偏移量值的结果
     */
    public Object offsetValue() {
        return offsetValue;
    }

    /**
    * 时间戳
    *
    * @return 时间戳的结果
     */
    public long timestamp() {
        return timestamp;
    }

    /**
    * mappingid
    *
    * @return mappingId的结果
     */
    public String mappingId() {
        return mappingId;
    }

    @Override
    /** 转为字符串 */
    public String toString() {
        return "SyncDataOffset{sourceId=" + sourceId + ", offset=" + offsetValue + ", ts=" + timestamp + ", mapping=" + mappingId + "}";
    }
}