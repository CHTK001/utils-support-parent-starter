package com.chua.datalake.support.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 采集数据模型。
 *
 * <p>承载从数据源采集到的原始数据记录，包含主题、协议与消息负载，
 * 由 {@link com.chua.datalake.support.spi.collection.ActiveCollector}
 * 推送给数据管道处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class CollectData {

    /** 数据主题（topic） */
    private String topic;

    /** 消息负载（原始数据） */
    private String payload;

    /** 数据源协议（如 KAFKA / CHRONICLE / MQTT） */
    private String protocol;

    /** 采集时间戳（毫秒） */
    private long timestamp;
}
