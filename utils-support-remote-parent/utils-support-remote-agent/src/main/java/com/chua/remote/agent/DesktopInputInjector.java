package com.chua.remote.agent;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 桌面键鼠事件注入器（RDP/VNC 桌面通道共享）。
 *
 * <p>将控制端下发的键鼠事件注入被控机桌面。使用 {@link java.awt.Robot} 实现真实注入：
 * 键盘按键（keyPress/keyRelease）、鼠标移动与点击（mouseMove/mousePress/mouseRelease）、滚轮。
 * 事件帧载荷为 JSON：{@code {"type":"key","keyCode":65,"pressed":true}}
 * 或 {@code {"type":"mouse","x":100,"y":200,"button":1,"pressed":true}}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DesktopInputInjector {

    /** 事件载荷中的事件类型字段 */
    private static final String FIELD_TYPE = "type";

    /** 事件载荷中的按键码字段 */
    private static final String FIELD_KEY_CODE = "keyCode";

    /** 事件载荷中的按下状态字段 */
    private static final String FIELD_PRESSED = "pressed";

    /** 事件载荷中的 x 坐标字段 */
    private static final String FIELD_X = "x";

    /** 事件载荷中的 y 坐标字段 */
    private static final String FIELD_Y = "y";

    /** 事件载荷中的鼠标按钮字段 */
    private static final String FIELD_BUTTON = "button";

    /** 键盘事件标识 */
    private static final String TYPE_KEY = "key";

    /** 鼠标事件标识 */
    private static final String TYPE_MOUSE = "mouse";

    /** 鼠标滚轮事件标识 */
    private static final String TYPE_WHEEL = "wheel";

    /** Robot 实例（系统桌面注入） */
    private final Robot robot;

    /**
     * 创建注入器。
     *
     * @throws RuntimeException 当环境不支持 Robot 注入时（如 headless）
     */
    public DesktopInputInjector() {
        try {
            this.robot = new Robot();
        } catch (Exception e) {
            throw new RuntimeException("Robot 初始化失败（headless 或权限不足）", e);
        }
    }

    /**
     * 注入一条键鼠事件。
     *
     * @param payload 事件载荷（JSON 文本）
     */
    public void inject(byte[] payload) {
        if (payload == null || payload.length == 0) {
            log.warn("注入事件载荷为空");
            return;
        }
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            Map<?, ?> event = Json.fromJson(json, Map.class);
            if (event == null) {
                log.warn("注入事件 JSON 解析为空");
                return;
            }
            dispatch(event);
        } catch (Exception e) {
            log.error("注入键鼠事件失败", e);
        }
    }

    /**
     * 按事件类型分发注入。
     *
     * @param event 事件映射
     */
    @SuppressWarnings("unchecked")
    private void dispatch(Map<?, ?> event) {
        String type = String.valueOf(event.get(FIELD_TYPE));
        switch (type) {
            case TYPE_KEY:
                injectKey((Map<String, Object>) event);
                break;
            case TYPE_MOUSE:
                injectMouse((Map<String, Object>) event);
                break;
            case TYPE_WHEEL:
                injectWheel((Map<String, Object>) event);
                break;
            default:
                log.warn("未知注入事件类型: {}", type);
                break;
        }
    }

    /**
     * 注入键盘事件。
     *
     * @param event 事件映射（keyCode/pressed）
     */
    private void injectKey(Map<String, Object> event) {
        int keyCode = toInt(event.get(FIELD_KEY_CODE));
        boolean pressed = Boolean.parseBoolean(String.valueOf(event.get(FIELD_PRESSED)));
        if (pressed) {
            robot.keyPress(keyCode);
        } else {
            robot.keyRelease(keyCode);
        }
        log.debug("注入按键: keyCode={}, pressed={}", keyCode, pressed);
    }

    /**
     * 注入鼠标事件。
     *
     * @param event 事件映射（x/y/button/pressed）
     */
    private void injectMouse(Map<String, Object> event) {
        int x = toInt(event.get(FIELD_X));
        int y = toInt(event.get(FIELD_Y));
        int button = toInt(event.get(FIELD_BUTTON));
        boolean pressed = Boolean.parseBoolean(String.valueOf(event.get(FIELD_PRESSED)));
        robot.mouseMove(x, y);
        int mask = toButtonMask(button);
        if (mask != 0 && pressed) {
            robot.mousePress(mask);
        }
        if (mask != 0 && !pressed) {
            robot.mouseRelease(mask);
        }
        log.debug("注入鼠标: x={}, y={}, button={}, pressed={}", x, y, button, pressed);
    }

    /**
     * 注入鼠标滚轮事件。
     *
     * @param event 事件映射（x/y/amount）
     */
    private void injectWheel(Map<String, Object> event) {
        int x = toInt(event.get(FIELD_X));
        int y = toInt(event.get(FIELD_Y));
        int amount = toInt(event.get("amount"));
        robot.mouseMove(x, y);
        robot.mouseWheel(amount);
        log.debug("注入滚轮: x={}, y={}, amount={}", x, y, amount);
    }

    /**
     * 按钮标识转 Robot 掩码。
     *
     * @param button 按钮标识（1 左 / 3 右 / 2 中）
     * @return Robot 掩码（未知按钮返回 0）
     */
    private int toButtonMask(int button) {
        switch (button) {
            case 1:
                return InputEvent.BUTTON1_DOWN_MASK;
            case 3:
                return InputEvent.BUTTON3_DOWN_MASK;
            case 2:
                return InputEvent.BUTTON2_DOWN_MASK;
            default:
                return 0;
        }
    }

    /**
     * 将对象安全转换为 int。
     *
     * @param value 对象值
     * @return int 值（转换失败返回 0）
     */
    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 关闭注入器。
     */
    public void close() {
        log.info("桌面注入器已关闭");
    }
}
