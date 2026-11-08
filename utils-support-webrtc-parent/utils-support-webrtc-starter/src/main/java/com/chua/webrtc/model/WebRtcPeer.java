package com.chua.webrtc.model;

import com.chua.webrtc.control.ControlEventListener;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * 点对点 WebRTC 连接。
 *
 * <p>一个 peer 对应一个浏览器连接，负责：
 * <ul>
 *   <li>发送桌面视频流到浏览器</li>
 *   <li>接收浏览器通过 DataChannel 传来的控制事件（键鼠/键盘）</li>
 *   <li>维护连接状态、统计信息</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface WebRtcPeer {

    /**
     * 连接ID，唯一标识一个点对点连接。
     *
     * @return 连接ID
     */
    String getId();

    /**
     * 会话ID。
     *
     * @return 会话ID
     */
    String getSessionId();

    /**
     * 获取 PeerConnection 状态。
     *
     * @return 连接状态
     */
    ConnectionState getState();

    /**
     * 是否已连接。
     *
     * @return true 表示已建立 WebRTC 连接
     */
    default boolean isConnected() {
        return ConnectionState.CONNECTED.equals(getState());
    }

    /**
     * 发送 SDP offer。
     *
     * @param offer sdp offer
     */
    void sendOffer(String offer);

    /**
     * 发送 SDP answer。
     *
     * @param answer sdp answer
     */
    void sendAnswer(String answer);

    /**
     * 发送 ICE candidate。
     *
     * @param candidate ice candidate
     */
    void sendCandidate(String candidate);

    /**
     * 在 DataChannel 上发送控制消息（键鼠事件向远端浏览器发送）。
     *
     * @param controlEvent 控制事件
     */
    void sendControl(ControlEvent controlEvent);

    /**
     * 获取连接的远端媒体流。
     *
     * @return 远端视频流
     */
    VideoTrack remoteTrack();

    /**
     * 添加远端媒体流监听。
     *
     * @param listener 视频流监听
     */
    void addRemoteTrackListener(MediaStreamTrackListener listener);

    /**
     * 添加控制事件监听。
     *
     * @param listener 控制事件监听
     */
    void addControlEventListener(ControlEventListener listener);

    /**
     * 标记连接为活跃（防止浏览器勘查时误杀）
     */
    void heartbeat();

    /**
     * 断开连接，释放 Peer 资源。
     */
    void disconnect();

    /**
     * 连接状态。
     */
    enum ConnectionState {
        NONE,        // 未创建
        CALLING,     // 协商中
        CONNECTED,   // 已连接
        FAILED,      // 协商失败
        DISCONNECTED // 已断开
    }
}
