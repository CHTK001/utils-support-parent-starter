package com.chua.webrtc.control;

import com.chua.webrtc.model.ControlEvent;

import java.util.List;

/**
 * 远端控制面板 SPI：接收浏览器端通过 DataChannel 发来的键鼠控制事件。
 *
 * <p>实现类负责将控制事件投递到 Java 进程中：
 * <ul>
 *   <li>鼠标 → java.awt.Robot / 平台级输入事件</li>
 *   <li>键盘 → Windows 按键发送 / X11 WLock</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ControlService {

    /**
     * SPI 名称。
     *
     * @return 服务名称
     */
    String spiName();

    /**
     * 注册控制事件监听器。
     *
     * @param listener 监听器
     */
    void register(ControlEventListener listener);

    /**
     * 移除控制事件监听器。
     *
     * @param listener 监听器
     */
    void unregister(ControlEventListener listener);

    /**
     * 获取已注册的全部监听器。
     *
     * @return 监听器列表
     */
    List<ControlEventListener> listeners();

    /**
     * 控制事件处理：从 DataChannel 接收事件后调用。
     *
     * @param sessionId 会话ID
     * @param peerId    连接ID
     * @param path      控制路径
     * @param event     控制事件
     */
    void onControlEvent(String sessionId, String peerId, String path, ControlEvent event);

    /**
     * 是否支持控制。
     *
     * @param sessionId 会话ID
     * @return true 表示支持
     */
    boolean isSupport(String sessionId);

    /**
     * 关闭。
     */
    void close();
}
