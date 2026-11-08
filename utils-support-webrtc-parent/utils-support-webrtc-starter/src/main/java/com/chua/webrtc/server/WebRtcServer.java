package com.chua.webrtc.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.webrtc.control.ControlService;
import com.chua.webrtc.model.WebRtcPeer;
import com.chua.webrtc.model.WebRtcSession;
import com.chua.webrtc.model.WebRtcSignalingRequest;
import com.chua.webrtc.model.WebRtcSignalingResponse;
import com.chua.webrtc.spi.WebRtcService;
import com.chua.webrtc.spi.media.ScreenCaptureService;
import com.chua.webrtc.capture.JavaScreenCaptureService;
import com.chua.webrtc.input.JavaInputSender;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.PeerConnectionFactory;

/**
 * 基于 webrtc-java 的 WebRTC 远控服务器。
 *
 * <p>同时承担两个角色：
 * <ul>
 *   <li>{@link com.chua.common.support.network.server.Server}：对外提供信令服务端（WebSocket），
 *       处理浏览器发来的 offer/answer/ice-candidate 信令</li>
 *   <li>{@link WebRtcService}：通过 {@link com.chua.common.support.spi.ServiceProvider} SPI 发现，
 *       给调用方提供会话 / 采集 / 控制 等远控能力</li>
 * </ul>
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 * WebRtcServer server = new WebRtcServer(ServerSetting.builder()
 *     .port(10000)
 *     .protocol(ProtocolType.WS)
 *     .build());
 * server.start();
 * }</pre>
 * 浏览器端在 <code>ws://host:10000/webrtc</code> 建立 WebSocket 连接后
 * 将在此服务端协商 WebRTC 连接、开启桌面采集、收发控制事件。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi("webrtc")
@SpiDefault
public class WebRtcServer extends AbstractServer implements WebRtcService {

    /**
     * 信令 WebSocket 路径。
     */
    public static final String SIGNALING_PATH = "/webrtc";

    /**
     * 活跃SESSION（session 已创建但连接未断开）
     */
    private final Map<String, WebRtcSession> sessions = new ConcurrentHashMap<>(16);

    /**
     * 会话 Socket 连接（用于向浏览器回发消息）
     */
    private final Map<String, Socket> sockets = new ConcurrentHashMap<>(16);

    /**
     * 屏幕采集服务
     */
    private final ScreenCaptureService screenCaptureService;

    /**
     * 控制注入服务
     */
    private final ControlService controlService;

    /**
     * 信令 Socket 监听器
     */
    private volatile ServerSocket signalingServerSocket;

    /**
     * 信令执行器（虚拟线程）
     */
    private volatile Thread signalingAcceptThread;

    /**
     * PeerConnectionFactory（webrtc-java 单例工厂）
     */
    private volatile PeerConnectionFactory peerConnectionFactory;

    /**
     * 是否自动关闭
     */
    private boolean autoClose = true;

    public WebRtcServer(ServerSetting setting) {
        super(setting);
        this.screenCaptureService = new JavaScreenCaptureService();
        this.controlService = new JavaInputSender();
    }

    public WebRtcServer() {
        this(ServerSetting.defaults());
    }

    /*
     * ============== WebRtcService ====================
     */

    @Override
    public String spiName() {
        return "webrtc";
    }

    @Override
    public Collection<WebRtcSession> sessions() {
        return sessions.values();
    }

    @Override
    public Optional<WebRtcSession> session(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public WebRtcSession createSession(String sessionId) {
        WebRtcSessionImpl session = new WebRtcSessionImpl(sessionId, this);
        sessions.put(sessionId, session);
        return session;
    }

    @Override
    public void destroySession(String sessionId) {
        WebRtcSession session = sessions.remove(sessionId);
        if (session != null) {
            session.destroy();
        }
    }

    @Override
    public List<WebRtcPeer> peers(String sessionId) {
        WebRtcSession session = sessions.get(sessionId);
        if (session == null) {
            return List.of();
        }
        return session.peers();
    }

    @Override
    public WebRtcSignalingResponse receiveSignaling(String sessionId, WebRtcSignalingRequest request) {
        if (WebRtcSignalingRequest.Type.OFFER.equals(request.type())) {
            createSessionAndPeer(sessionId, request.payload());
            return WebRtcSignalingResponse.of(WebRtcSignalingResponse.Type.OK, "{}");
        }
        if (WebRtcSignalingRequest.Type.ANSWER.equals(request.type())) {
            session(sessionId).ifPresent(s -> s.peers().forEach(p -> p.sendAnswer(request.payload())));
            return WebRtcSignalingResponse.of(WebRtcSignalingResponse.Type.OK, "{}");
        }
        if (WebRtcSignalingRequest.Type.CANDIDATE.equals(request.type())) {
            session(sessionId).ifPresent(s -> s.peers().forEach(p -> p.sendCandidate(request.payload())));
            return WebRtcSignalingResponse.of(WebRtcSignalingResponse.Type.OK, "{}");
        }
        return WebRtcSignalingResponse.of(WebRtcSignalingResponse.Type.OK, "{}");
    }

    /**
     * 创建 Session 与 Peer，并开始 SDP 协商
     */
    private void createSessionAndPeer(String sessionId, String offer) {
        WebRtcSession session = sessions.computeIfAbsent(sessionId, s -> createSession(s));
        WebRtcPeerImpl peer = new WebRtcPeerImpl("peer-0", sessionId, this, peerConnectionFactory);
        session.addPeer(peer);
        peer.sendOffer(offer);
    }

    @Override
    public boolean isRunning() {
        return super.isRunning();
    }

    /*
     * ============== Server 接口 ====================
     */

    /**
     * 注册信令连接（用于向浏览器回发消息）
     *
     * @param sessionId 会话ID
     * @param socket 对应的 Socket 连接
     */
    public void register(String sessionId, Socket socket) {
        sockets.put(sessionId, socket);
    }

    /**
     * 向指定会话的浏览器端发送信令
     *
     * @param sessionId 会话ID
     * @param json JSON 信令
     */
    public void sendJson(String sessionId, String json) {
        Socket socket = sockets.get(sessionId);
        if (socket != null) {
            try {
                if (socket.isConnected() && !socket.isClosed()) {
                    WebRtcSignalingHandler.writeFrame(socket, json);
                }
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.WS;
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public Extensions extensions() {
        return new DefaultExtensions();
    }

    /**
     * 扩展服务默认实现
     */
    private class DefaultExtensions implements Extensions {
        @Override
        public ScreenCaptureService screenCaptureService() {
            return screenCaptureService;
        }

        @Override
        public ControlService controlService() {
            return controlService;
        }
    }

    /*
     * ============== doStart / doStop ====================
     */

    @Override
    protected void doStart() {
        try {
            // 创建 WebRTC 工厂
            peerConnectionFactory = new PeerConnectionFactory();
            ((JavaScreenCaptureService) screenCaptureService).init(peerConnectionFactory);

            // 启动 WebSocket 信令监听
            signalingServerSocket = new ServerSocket();
            signalingServerSocket.setReuseAddress(true);
            signalingServerSocket.bind(new java.net.InetSocketAddress(setting.getHost(), setting.getPort()));
            signalingAcceptThread = new Thread(this::acceptLoop, "webrtc-signal-acceptor");
            signalingAcceptThread.setDaemon(true);
            signalingAcceptThread.start();
        } catch (Exception e) {
            throw new com.chua.webrtc.error.WebRtcException("WebRTC signaling server start failed: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doStop() {
        // 销毁全部会话
        sessions.values().forEach(WebRtcSession::destroy);
        sessions.clear();
        // 停止信令
        stopSignaling();
    }

    /**
     * 停止信令
     */
    private void stopSignaling() {
        try {
            if (signalingServerSocket != null && !signalingServerSocket.isClosed()) {
                signalingServerSocket.close();
            }
        } catch (Exception ignored) {
            // ignore
        }
        try {
            if (signalingAcceptThread != null) {
                signalingAcceptThread.join(TimeUnit.SECONDS.toMillis(3));
            }
        } catch (InterruptedException ignored) {
            // ignore
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
    }

    /**
     * Accept Loop
     */
    private void acceptLoop() {
        while (!signalingServerSocket.isClosed()) {
            try {
                Socket socket = signalingServerSocket.accept();
                Thread thread = new Thread(() -> WebRtcSignalingHandler.handleConnection(socket, WebRtcServer.this));
                thread.setDaemon(true);
                thread.start();
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    /**
     * 获取配置信息
     */
    public boolean isAutoClose() {
        return autoClose;
    }

    /**
     * 设置自动关闭
     */
    public void setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
    }

    /**
     * PeerConnectionFactory 暴露（给 Peer 创建连接）
     *
     * @return 工厂
     */
    public PeerConnectionFactory peerConnectionFactory() {
        return peerConnectionFactory;
    }
}
