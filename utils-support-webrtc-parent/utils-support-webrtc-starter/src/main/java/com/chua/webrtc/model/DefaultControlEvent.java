package com.chua.webrtc.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 控制事件默认实现。
 *
 * <p>浏览器通过 DataChannel 发来的事件 JSON 会被反序列化为子类
 * （{@link DefaultMouseEvent} / {@link DefaultKeyboardEvent}），
 * 字段使用 {@link JsonProperty} 显式绑定，保证根节点字段可直接映射。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class DefaultControlEvent implements ControlEvent {

    /**
     * 事件类型
     */
    @JsonProperty("type")
    private String type;

    /**
     * 事件路径
     */
    @JsonProperty("path")
    private String path;

    /**
     * 时间戳
     */
    @JsonProperty("timestamp")
    private long timestamp;

    /**
     * 默认构造函数（JSON 反序列化）
     */
    public DefaultControlEvent() {
        this(null, null, 0L);
    }

    /**
     * 参数化构造函数
     *
     * @param type      事件类型
     * @param path      事件路径
     * @param timestamp 时间戳
     */
    public DefaultControlEvent(String type, String path, long timestamp) {
        this.type = type;
        this.path = path;
        this.timestamp = timestamp;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }
}
