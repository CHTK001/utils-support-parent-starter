package com.chua.webrtc.model;

/**
 * 控制事件：从浏览 DataChannel 发往 Java 服务端的远端控制操作。
 *
 * <p>浏览器端（JavaScript）包装键鼠状态为 JSON，经 DataChannel 发送给 Java 服务端。
 * Java 端收到后通过 {@link com.chua.webrtc.control.ControlService} 注入到本机。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ControlEvent {

    /**
     * 事件类型：mouse / keyboard
     */
    interface Type {
        String MOUSE = "mouse";
        String KEYBOARD = "keyboard";
    }

    /**
     * 鼠标按键：左键
     */
    String BUTTON_LEFT = Type.MOUSE + "-left";

    /**
     * 鼠标按键：中键
     */
    String BUTTON_MIDDLE = Type.MOUSE + "-middle";

    /**
     * 鼠标按键：右键
     */
    String BUTTON_RIGHT = Type.MOUSE + "-right";

    /**
     * 鼠标动作：按下
     */
    String ACTION_PRESS = "press";

    /**
     * 鼠标动作：抬起
     */
    String ACTION_RELEASE = "release";

    /**
     * 鼠标动作：移动
     */
    String ACTION_MOVE = "move";

    /**
     * 鼠标动作：滚轮
     */
    String ACTION_WHEEL = "wheel";

    /**
     * 键盘动作：按下
     */
    String ACTION_DOWN = "down";

    /**
     * 键盘动作：松开
     */
    String ACTION_UP = "up";

    /**
     * 当前事件类型。
     *
     * @return 事件类型
     */
    String type();

    /**
     * 当前事件路径（如 control、video、tracking）
     *
     * @return 事件路径
     */
    String path();

    /**
     * 事件发生的时间戳（毫秒）。
     *
     * @return 时间戳
     */
    long timestamp();

    /**
     * 鼠标事件，包含坐标 + 按键状态。
     */
    interface MouseEvent extends ControlEvent {
        /**
         * 鼠标横坐标（屏幕空间）。
         */
        int x();

        /**
         * 鼠标纵坐标（屏幕空间）。
         */
        int y();

        /**
         * 鼠标按键：left / middle / right。
         */
        String button();

        /**
         * 按键动作：press / release / move / wheel。
         */
        String action();

        /**
         * 滚轮滚动数量。
         */
        int delta();
    }

    /**
     * 键盘事件，包含键码 + 修饰键。
     */
    interface KeyboardEvent extends ControlEvent {
        /**
         * 键码（Java 虚拟键码）。
         */
        int keyCode();

        /**
         * 按键动作：down / up。
         */
        String action();

        /**
         * 是否按下 Control 键。
         */
        boolean isControl();

        /**
         * 是否按下 Alt 键。
         */
        boolean isAlt();

        /**
         * 是否按下 Shift 键。
         */
        boolean isShift();

        /**
         * 是否按下 Meta 键。
         */
        boolean isMeta();
    }

    /**
     * 控制事件工厂：将 JSON 反序列化为 ControlEvent 实例。
     */
    interface ControlEventFactory {
        /**
         * 将 JSON 文本转为 ControlEvent 实例。
         *
         * @param json JSON 文本
         * @return ControlEvent 实例
         */
        ControlEvent parse(String json);
    }
}
