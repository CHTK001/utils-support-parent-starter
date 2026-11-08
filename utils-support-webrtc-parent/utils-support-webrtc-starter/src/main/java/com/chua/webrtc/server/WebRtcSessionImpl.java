package com.chua.webrtc.server;

import com.chua.webrtc.model.WebRtcPeer;
import com.chua.webrtc.model.WebRtcSession;
import com.chua.webrtc.spi.WebRtcService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 实现，管理一个浏览器连接的全部 Peer。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class WebRtcSessionImpl implements WebRtcSession {

    /** 会话ID */
    private final String sessionId;

    /** Peer 集合 */
    private final Map<String, WebRtcPeer> peers = new ConcurrentHashMap<>(4);

    /** 关联的服务 */
    private final WebRtcService service;

    public WebRtcSessionImpl(String sessionId, WebRtcService service) {
        this.sessionId = sessionId;
        this.service = service;
    }

    @Override
    public String getId() {
        return sessionId;
    }

    @Override
    public void addPeer(WebRtcPeer peer) {
        peers.put(peer.getId(), peer);
    }

    @Override
    public void removePeer(WebRtcPeer peer) {
        peers.remove(peer.getId());
    }

    @Override
    public WebRtcPeer peer(String peerId) {
        return peers.get(peerId);
    }

    @Override
    public void destroy() {
        peers.forEach((_, peer) -> peer.disconnect());
        peers.clear();
    }

    @Override
    public List<WebRtcPeer> peers() {
        return new ArrayList<>(peers.values());
    }
}
