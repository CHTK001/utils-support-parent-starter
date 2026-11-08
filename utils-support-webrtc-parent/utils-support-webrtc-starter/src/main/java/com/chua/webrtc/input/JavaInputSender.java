package com.chua.webrtc.input;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.webrtc.control.ControlEventListener;
import com.chua.webrtc.control.ControlService;
import com.chua.webrtc.model.ControlEvent;

import java.awt.AWTException;
import java.awt.Robot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将浏览器的控制事件（键鼠）作用于本机系统的 Java 实现。
 *
 * <p>使用 {@link java.awt.Robot} 完成底层注入：
 * <ul>
 *   <li>鼠标移动/点击/滚轮 → {@link java.awt.Robot#mouseMove(int, int)}</li>
 *   <li>键盘按下/松开 → {@link java.awt.Robot#keyPress(int)} / {@link java.awt.Robot#keyRelease(int)}</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi(value = "webrtc.input-sender", order = 10)
@SpiDefault
public class JavaInputSender implements ControlService {

    /** SPI 名称 */
    public static final String SPI_NAME = "webrtc.input-sender";

    /** 控制通道路径 */
    public static final String CONTROL_PATH = "control";

    /** AWT Robot */
    private final Robot robot;

    /** 事件监听器 */
    private final List<ControlEventListener> listeners = new ArrayList<>();

    public JavaInputSender() {
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            throw new UnavailableSystemInput("Could not create Robot", e);
        }
    }

    @Override
    public String spiName() {
        return SPI_NAME;
    }

    @Override
    public void register(ControlEventListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    @Override
    public void unregister(ControlEventListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public List<ControlEventListener> listeners() {
        return Collections.unmodifiableList(listeners);
    }

    @Override
    public void onControlEvent(String sessionId, String peerId, String path, ControlEvent event) {
        if (!CONTROL_PATH.equals(path)) {
            return;
        }
        for (ControlEventListener listener : listeners) {
            try {
                listener.onControlEvent(sessionId, peerId, event);
            } catch (Exception ignored) {
                // 业务层自主处理异常
            }
        }
        inject(event);
    }

    @Override
    public boolean isSupport(String sessionId) {
        return true;
    }

    @Override
    public void close() {
        synchronized (listeners) {
            listeners.clear();
        }
    }

    /**
     * 将控制事件注入到本机。
     *
     * @param event 控制事件
     */
    protected void inject(ControlEvent event) {
        switch (event.type()) {
            case ControlEvent.Type.MOUSE -> injectMouse((ControlEvent.MouseEvent) event);
            case ControlEvent.Type.KEYBOARD -> injectKeyboard((ControlEvent.KeyboardEvent) event);
            default -> throw new UnsupportedOperationException("Unsupported ControlEvent type: " + event.type());
        }
    }

    /**
     * 注入鼠标。
     *
     * @param mouseEvent 鼠标事件
     */
    protected void injectMouse(ControlEvent.MouseEvent mouseEvent) {
        switch (mouseEvent.action()) {
            case ControlEvent.ACTION_MOVE -> robot.mouseMove(mouseEvent.x(), mouseEvent.y());
            case ControlEvent.ACTION_WHEEL -> robot.mouseWheel(mouseEvent.delta());
            case ControlEvent.ACTION_PRESS -> robot.mousePress(mouseButton(mouseEvent.button()));
            case ControlEvent.ACTION_RELEASE -> robot.mouseRelease(mouseButton(mouseEvent.button()));
            default -> throw new UnsupportedOperationException("Unknown mouse action: " + mouseEvent.action());
        }
    }

    /**
     * 注入键盘。
     *
     * @param keyboardEvent 键盘事件
     */
    protected void injectKeyboard(ControlEvent.KeyboardEvent keyboardEvent) {
        if (ControlEvent.ACTION_DOWN.equals(keyboardEvent.action())) {
            robot.keyPress(keyboardEvent.keyCode());
        } else {
            robot.keyRelease(keyboardEvent.keyCode());
        }
    }

    /**
     * 将 WebRTC 传来的按键名映射为 AWT 按钮常量。
     *
     * <p>同时兼容 {@code "mouse-left"}（{@link ControlEvent#BUTTON_LEFT}）与浏览器常用的
     * {@code "left"} / {@code "middle"} / {@code "right"} 写法。</p>
     */
    private int mouseButton(String button) {
        String name = button == null ? "" : button.toLowerCase();
        return switch (name) {
            case ControlEvent.BUTTON_LEFT, "left" -> java.awt.event.MouseEvent.BUTTON1;
            case ControlEvent.BUTTON_MIDDLE, "middle" -> java.awt.event.MouseEvent.BUTTON2;
            case ControlEvent.BUTTON_RIGHT, "right" -> java.awt.event.MouseEvent.BUTTON3;
            default -> java.awt.event.MouseEvent.BUTTON1;
        };
    }
}
