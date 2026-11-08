package com.chua.webrtc.model;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;

/**
 * 控制事件工厂：将控制事件 JSON 反序列化为对应的 ControlEvent 实例。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class DefaultControlEventFactory implements ControlEvent.ControlEventFactory {

    /** 事件类型：鼠标 */
    private static final String TYPE_MOUSE = "mouse";

    /** 事件类型：键盘 */
    private static final String TYPE_KEYBOARD = "keyboard";

    @Override
    public ControlEvent parse(String json) {
        String type;
        try {
            type = Json.fromJson(json, JsonObject.class).getType("type", null, String.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse control event: " + e.getMessage());
        }
        switch (type) {
            case TYPE_MOUSE:
                return Json.fromJson(json, DefaultMouseEvent.class);
            case TYPE_KEYBOARD:
                return Json.fromJson(json, DefaultKeyboardEvent.class);
            default:
                throw new IllegalArgumentException("Unsupported control event type: " + type);
        }
    }
}
