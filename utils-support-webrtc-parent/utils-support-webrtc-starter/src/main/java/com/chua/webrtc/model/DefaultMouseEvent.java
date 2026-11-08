package com.chua.webrtc.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 鼠标控制事件默认实现。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class DefaultMouseEvent extends DefaultControlEvent implements ControlEvent.MouseEvent {

    /**
     * 鼠标 X 坐标
     */
    @JsonProperty("x")
    private int x;

    /**
     * 鼠标 Y 坐标
     */
    @JsonProperty("y")
    private int y;

    /**
     * 鼠标按键
     */
    @JsonProperty("button")
    private String button;

    /**
     * 鼠标动作
     */
    @JsonProperty("action")
    private String action;

    /**
     * 滚轮增量
     */
    @JsonProperty("delta")
    private int delta;

    /**
     * 默认构造函数（JSON 反序列化）
     */
    public DefaultMouseEvent() {
        super();
    }

    /**
     * 参数化构造函数
     *
     * @param type      事件类型
     * @param path      事件路径
     * @param timestamp 时间戳
     * @param x         X 坐标
     * @param y         Y 坐标
     * @param button    按键
     * @param action    动作
     * @param delta     滚轮增量
     */
    public DefaultMouseEvent(String type, String path, long timestamp, int x, int y, String button, String action, int delta) {
        super(type, path, timestamp);
        this.x = x;
        this.y = y;
        this.button = button;
        this.action = action;
        this.delta = delta;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public String button() {
        return button;
    }

    @Override
    public String action() {
        return action;
    }

    @Override
    public int delta() {
        return delta;
    }
}
