package com.chua.webrtc.control;

import com.chua.webrtc.model.ControlEvent;

/**
 * 委制控制事件侦听器。
 *
 * <p>实现类负责将接收到的控制事件应用到本地系统（Windows/Linux/macOS）。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ControlEventListener {

    /**
     * 处理到来的控制事件。
     *
     * @param sessionId 会话ID
     * @param peerId    连接ID
     * @param event     控制事件
     * @return true 表示已消费
     */
    boolean onControlEvent(String sessionId, String peerId, ControlEvent event);
}
