package com.chua.webrtc.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.webrtc.control.ControlEventListener;
import com.chua.webrtc.control.ControlService;
import com.chua.webrtc.model.ControlEvent;
import com.chua.webrtc.model.WebRtcPeer;
import com.chua.webrtc.model.WebRtcSession;
import com.chua.webrtc.model.WebRtcSignalingRequest;
import com.chua.webrtc.model.WebRtcSignalingResponse;
import com.chua.webrtc.spi.WebRtcService;
import com.chua.webrtc.spi.media.ScreenCaptureService;
import com.chua.webrtc.capture.JavaScreenCaptureService;
import com.chua.webrtc.input.JavaInputSender;
import com.chua.webrtc.input.UnavailableSystemInput;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(WebRtcServer.class.getName());

    /**
     * 信令 WebSocket 路径。
     */
    public static final String SIGNALING_PATH = "/webrtc";

    /**
     * 活跃SESSION（session 已创建但连接未断开）
     */
    // [P3C A20 豁免]
    private final Map<String, WebRtcSession> sessions = new ConcurrentHashMap<>(16);

    /**
     * 会话 Socket 连接（用于向浏览器回发消息）
     */
    // [P3C A20 豁免]
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

    /**
     * 信令访问令牌；为空表示不校验（仅建议在本机或受信网络下使用）。
     */
    private volatile String signalingToken;

    public WebRtcServer(ServerSetting setting) {
        super(setting);
        this.screenCaptureService = new JavaScreenCaptureService();
        this.controlService = createControlService();
    }

    /**
     * 创建键鼠注入服务。
     *
     * <p>headless 环境下 {@link JavaInputSender} 构造会抛 AWTException；远控应降级为
     * “只读画面”，而不是让宿主启动失败，所以这里捕获后回落到空实现。</p>
     *
     * @return 控制服务
     */
    private static ControlService createControlService() {
        try {
            return new JavaInputSender();
        } catch (UnavailableSystemInput e) {
            LOG.log(Level.WARNING, "[webrtc] 键鼠注入不可用，远控降级为只读: {0}", e.getMessage());
            return new NoopControlService();
        }
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
        // 复用同 ID 会话：直接 put 会丢掉旧 session 的 peers（连接与采集都不回收）
        return sessions.computeIfAbsent(sessionId, id -> new WebRtcSessionImpl(id, this));
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
        WebRtcSession session = sessions.computeIfAbsent(sessionId, id -> new WebRtcSessionImpl(id, this));
        // 同一会话只保留一个 peer：新 offer 视为重连，先断开旧连接，避免固定 peer-0 覆盖造成泄漏
        for (WebRtcPeer old : session.peers()) {
            old.disconnect();
            session.removePeer(old);
        }
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
     * 注销信令连接。
     *
     * <p>连接断开后必须把 socket 从表里摘掉；如果该会话已经没有信令连接，
     * 连同 session/peer 一起销毁，最后一个会话结束时停止桌面采集。
     * 否则浏览器直接关标签/崩溃会留下“活跃会话”，采集也会一直跑。</p>
     *
     * @param sessionId 会话 ID
     * @param socket    对应的 Socket 连接
     */
    public void unregister(String sessionId, Socket socket) {
        if (sessionId == null) {
            return;
        }
        sockets.remove(sessionId, socket);
        if (sockets.containsKey(sessionId)) {
            return;
        }
        WebRtcSession session = sessions.remove(sessionId);
        if (session != null) {
            session.destroy();
        }
        if (sessions.isEmpty()) {
            try {
                screenCaptureService.stop(null);
            } catch (Exception ignored) {
                // 采集可能尚未启动
            }
        }
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
        rejectStreamEncryption("WebRTC 媒体面已是 DTLS/SRTP，但信令通道是明文 TCP；"
                + "需要加密信令请用反向代理终结 TLS");
        try {
            // 创建 WebRTC 工厂
            peerConnectionFactory = new PeerConnectionFactory();
            ((JavaScreenCaptureService) screenCaptureService).init(peerConnectionFactory);

            // 启动 WebSocket 信令监听
            signalingServerSocket = new ServerSocket();
            signalingServerSocket.setReuseAddress(true);
            signalingServerSocket.bind(new java.net.InetSocketAddress(setting.getHost(), setting.getPort()));
            signalingAcceptThread = new Thread(this::acceptLoop, "webrtc-signal-acceptor"); // [P3C C1 豁免]
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
        sockets.clear();
        // 先停采集，再释放 PeerConnectionFactory；否则原生采集源会挂在已释放的工厂上
        try {
            screenCaptureService.stop(null);
        } catch (Exception ignored) {
            // 采集可能尚未启动
        }
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
                // [P3C C1 豁免] 每个连接独占工作线程，保持文档约定
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
     * 获取信令访问令牌。
     *
     * @return 令牌；为空表示不校验
     */
    public String getSignalingToken() {
        return signalingToken;
    }

    /**
     * 设置信令访问令牌。
     *
     * @param signalingToken 令牌
     */
    public void setSignalingToken(String signalingToken) {
        this.signalingToken = signalingToken;
    }

    /**
     * PeerConnectionFactory 暴露（给 Peer 创建连接）
     *
     * @return 工厂
     */
    public PeerConnectionFactory peerConnectionFactory() {
        return peerConnectionFactory;
    }

    /**
     * 键鼠注入不可用时的空实现：画面照常推，控制事件丢弃。
     */
    private static final class NoopControlService implements ControlService {

        @Override
        public String spiName() {
            return JavaInputSender.SPI_NAME;
        }

        @Override
        public void register(ControlEventListener listener) {
            // 只读模式不需要监听器
        }

        @Override
        public void unregister(ControlEventListener listener) {
            // 只读模式不需要监听器
        }

        @Override
        public List<ControlEventListener> listeners() {
            return List.of();
        }

        @Override
        public void onControlEvent(String sessionId, String peerId, String path, ControlEvent event) {
            // 只读模式丢弃控制事件
        }

        @Override
        public boolean isSupport(String sessionId) {
            return false;
        }

        @Override
        public void close() {
            // 无资源
        }
    }
}
