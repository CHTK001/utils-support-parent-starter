package com.chua.webrtc.model;

import java.util.List;

/**
 * 会话，管理一个远控连接的全部 Peer。
 *
 * <p>浏览器通过 WebSocket 控制信令，通过 WebRTC 传输媒体 + 数据通道。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface WebRtcSession {

    /**
     * 会话ID。
     *
     * @return 会话ID
     */
    String getId();

    /**
     * 添加点对点连接。
     *
     * @param peer 连接
     */
    void addPeer(WebRtcPeer peer);

    /**
     * 移除点对点连接。
     *
     * @param peer 连接
     */
    void removePeer(WebRtcPeer peer);

    /**
     * 根据连接ID查找 Peer。
     *
     * @param peerId 连接ID
     * @return 找到的 peer
     */
    WebRtcPeer peer(String peerId);

    /**
     * 获取该会话的全部连接点。
     *
     * @return 连接点集合
     */
    List<WebRtcPeer> peers();

    /**
     * 销毁会话。
     */
    void destroy();
}
