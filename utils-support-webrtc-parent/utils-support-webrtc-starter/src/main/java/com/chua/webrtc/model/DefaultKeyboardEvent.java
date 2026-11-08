package com.chua.webrtc.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 键盘控制事件默认实现。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class DefaultKeyboardEvent extends DefaultControlEvent implements ControlEvent.KeyboardEvent {

    /**
     * 键码（Java KeyEvent 键码）
     */
    @JsonProperty("keyCode")
    private int keyCode;

    /**
     * 动作
     */
    @JsonProperty("action")
    private String action;

    /**
     * 是否按下 Control
     */
    @JsonProperty("control")
    private boolean control;

    /**
     * 是否按下 Alt
     */
    @JsonProperty("alt")
    private boolean alt;

    /**
     * 是否按下 Shift
     */
    @JsonProperty("shift")
    private boolean shift;

    /**
     * 是否按下 Meta
     */
    @JsonProperty("meta")
    private boolean meta;

    /**
     * 默认构造函数（JSON 反序列化）
     */
    public DefaultKeyboardEvent() {
        super();
    }

    /**
     * 参数化构造函数
     *
     * @param type      事件类型
     * @param path      事件路径
     * @param timestamp 时间戳
     * @param keyCode   键码
     * @param action    动作
     * @param control   是否按下 Control
     * @param alt       是否按下 Alt
     * @param shift     是否按下 Shift
     * @param meta      是否按下 Meta
     */
    public DefaultKeyboardEvent(String type, String path, long timestamp, int keyCode, String action,
                                boolean control, boolean alt, boolean shift, boolean meta) {
        super(type, path, timestamp);
        this.keyCode = keyCode;
        this.action = action;
        this.control = control;
        this.alt = alt;
        this.shift = shift;
        this.meta = meta;
    }

    @Override
    public int keyCode() {
        return keyCode;
    }

    @Override
    public String action() {
        return action;
    }

    @Override
    public boolean isControl() {
        return control;
    }

    @Override
    public boolean isAlt() {
        return alt;
    }

    @Override
    public boolean isShift() {
        return shift;
    }

    @Override
    public boolean isMeta() {
        return meta;
    }
}
