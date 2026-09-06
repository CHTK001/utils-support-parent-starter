package com.chua.remote.controller;

import lombok.extern.slf4j.Slf4j;

/**
 * 键鼠事件注入器。
 *
 * <p>将控制端的键鼠事件注入到被控端系统。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InputInjector {

    /**
     * 注入键鼠事件。
     *
     * @param eventData 事件数据（包含按键码、坐标等）
     */
    public void inject(byte[] eventData) {
        log.debug("注入键鼠事件: size={}", eventData.length);
    }

    /**
     * 注入键盘按键。
     *
     * @param keyCode 按键码
     * @param pressed 是否按下
     */
    public void injectKey(int keyCode, boolean pressed) {
        log.debug("注入按键: keyCode={}, pressed={}", keyCode, pressed);
    }

    /**
     * 注入鼠标移动。
     *
     * @param x x 坐标
     * @param y y 坐标
     */
    public void injectMouseMove(int x, int y) {
        log.debug("注入鼠标移动: x={}, y={}", x, y);
    }

    /**
     * 注入鼠标点击。
     *
     * @param x     x 坐标
     * @param y     y 坐标
     * @param button 按钮
     */
    public void injectMouseClick(int x, int y, int button) {
        log.debug("注入鼠标点击: x={}, y={}, button={}", x, y, button);
    }
}
