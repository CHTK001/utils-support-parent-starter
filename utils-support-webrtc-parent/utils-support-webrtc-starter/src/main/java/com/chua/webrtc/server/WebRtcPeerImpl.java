package com.chua.webrtc.server;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.webrtc.control.ControlEventListener;
import com.chua.webrtc.control.ControlService;
import com.chua.webrtc.model.ControlEvent;
import com.chua.webrtc.model.DefaultControlEventFactory;
import com.chua.webrtc.model.MediaStreamTrackListener;
import com.chua.webrtc.model.WebRtcPeer;
import com.chua.webrtc.model.WebRtcSignalingRequest;
import com.chua.webrtc.model.WebRtcSignalingResponse;
import com.chua.webrtc.spi.WebRtcService;
import com.chua.webrtc.spi.media.ScreenCaptureService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * 基于 webrtc-java 的 WebRtcPeer 实现。
 *
 * <p>一条 Peer 对应一个浏览器连接，生命周期：
 * <ul>
 *   <li>收到 offer → 创建 RTCPeerConnection</li>
 *   <li>收到 answer/ICE candidate → 建立连接</li>
 *   <li>连接成功 → 启动桌面采集，推送视频轨道</li>
 *   <li>收到控制事件 → 注入到本机</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class WebRtcPeerImpl implements WebRtcPeer {

    /** 连接ID */
    private final String id;

    /** 会话ID */
    private final String sessionId;

    /** 关联服务 */
    private final WebRtcService service;

    /** 运行时状态 */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** 当前连接状态 */
    private ConnectionState state = ConnectionState.NONE;

    /** 工厂 */
    private final PeerConnectionFactory factory;

    /** 远端 Track 监听 */
    private final List<MediaStreamTrackListener> trackListeners = new CopyOnWriteArrayList<>();

    /** 控制事件监听 */
    private final List<ControlEventListener> controlListeners = new CopyOnWriteArrayList<>();

    /** 控制事件处理 */
    private final ControlService controlService;

    /** 连接 */
    private volatile RTCPeerConnection peerConnection;

    /** 桌面视频轨道 */
    private volatile VideoTrack videoTrack;

    /** 控制数据通道 */
    private volatile RTCDataChannel dataChannel;

    public WebRtcPeerImpl(String id, String sessionId, WebRtcService service, PeerConnectionFactory factory) {
        this.id = id;
        this.sessionId = sessionId;
        this.service = service;
        this.factory = factory;
        this.controlService = service.extensions().controlService();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public ConnectionState getState() {
        return state;
    }

    @Override
    public void sendOffer(String offer) {
        state = ConnectionState.CALLING;
        createPeerConnection();
        // 添加桌面视频轨道（必须在创建 Answer 之前）
        addVideoTrack();
        // 设置远端描述（OFFER）成功后再创建 Answer，避免 setRemoteDescription 尚未生效
        setRemoteDescription(offer, RTCSdpType.OFFER, this::createAnswer);
    }

    @Override
    public void sendAnswer(String answer) {
        // 设置远端描述（Answer 侧）
        setRemoteDescription(answer, RTCSdpType.ANSWER, () -> {
            state = ConnectionState.CONNECTED;
            connected.set(true);
        });
    }

    @Override
    public void sendCandidate(String candidate) {
        // 处理 ICE candidate
        if (peerConnection == null) {
            return;
        }
        try {
            JsonObject json = Json.fromJson(candidate, JsonObject.class);
            RTCIceCandidate iceCandidate = new RTCIceCandidate(
                    json.getType("sdpMid", "0", String.class),
                    json.getType("sdpMLineIndex", 0, Integer.class),
                    json.getType("sdp", "", String.class));
            peerConnection.addIceCandidate(iceCandidate);
        } catch (Exception e) {
            // 忽略非法候选
        }
    }

    @Override
    public void sendControl(ControlEvent controlEvent) {
        // 通过 DataChannel 发送控制事件到对端
        if (dataChannel != null && dataChannel.getState() == RTCDataChannelState.OPEN) {
            String json = Json.toJson(controlEvent);
            ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            try {
                dataChannel.send(new RTCDataChannelBuffer(bytes, false));
            } catch (Exception e) {
                // 忽略发送异常
            }
        }
    }

    @Override
    public VideoTrack remoteTrack() {
        return videoTrack;
    }

    @Override
    public void addRemoteTrackListener(MediaStreamTrackListener listener) {
        trackListeners.add(listener);
    }

    @Override
    public void addControlEventListener(ControlEventListener listener) {
        controlListeners.add(listener);
    }

    @Override
    public void heartbeat() {
        // 保证连接不挂断
    }

    @Override
    public void disconnect() {
        connected.set(false);
        state = ConnectionState.DISCONNECTED;
        // 断开数据通道
        if (dataChannel != null) {
            dataChannel.close();
            dataChannel = null;
        }
        // 释放 PeerConnection
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
    }

    /**
     * 创建 PeerConnection
     */
    private void createPeerConnection() {
        if (factory == null) {
            return;
        }
        try {
            peerConnection = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    // 把 ICE 候选通过信令发回对端
                    sendJson(WebRtcSignalingRequest.Type.CANDIDATE, iceCandidateJson(candidate));
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    // 连接状态变更
                    switch (state) {
                        case RTCPeerConnectionState.CONNECTED -> {
                            connected.set(true);
                            WebRtcPeerImpl.this.state = ConnectionState.CONNECTED;
                        }
                        default -> {
                        }
                    }
                }
            });
            // 创建控制数据通道
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            dataChannel = peerConnection.createDataChannel("control", init);
            dataChannel.registerObserver(new ControlDataChannelObserver());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create peer connection", e);
        }
    }

    /**
     * 桌面视频轨道（采集源与轨道绑定）
     */
    private void addVideoTrack() {
        try {
            ScreenCaptureService capture = service.extensions().screenCaptureService();
            capture.start(remoteTrack());
            // PeerConnection 使用采集源创建轨道
            VideoTrack track = capture.videoTrack();
            if (peerConnection != null && track != null) {
                peerConnection.addTrack(track, List.of("videoTrack"));
                videoTrack = track;
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 设置远端描述（offer / answer）
     *
     * @param sdp        SDP 文本
     * @param sdpType    SDP 类型
     * @param onSuccess  设置成功后的回调（如创建 Answer）
     */
    private void setRemoteDescription(String sdp, RTCSdpType sdpType, Runnable onSuccess) {
        if (peerConnection == null) {
            return;
        }
        RTCSessionDescription description = new RTCSessionDescription(sdpType, sdp);
        peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }

            @Override
            public void onFailure(String message) {
                // 忽略
            }
        });
    }

    /**
     * 创建 Answer（远端携带桌面视频流信息）
     */
    private void createAnswer() {
        if (peerConnection == null) {
            return;
        }
        peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                // setLocalDescription
                peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        // 发送 Answer 回到浏览器
                        sendJson(WebRtcSignalingRequest.Type.ANSWER, description.sdp);
                    }

                    @Override
                    public void onFailure(String message) {
                        // 忽略
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                // 忽略
            }
        });
    }

    /**
     * 将 ICE candidate 序列化为信令载荷
     */
    private String iceCandidateJson(RTCIceCandidate candidate) {
        JsonObject json = Json.createJsonObject();
        json.fluentPut("sdpMid", candidate.sdpMid);
        json.fluentPut("sdpMLineIndex", candidate.sdpMLineIndex);
        json.fluentPut("sdp", candidate.sdp);
        return json.toJSONString();
    }

    /**
     * 发送信令到浏览器
     */
    private void sendJson(String type, String payload) {
        try {
            WebRtcServer server = (WebRtcServer) service;
            if (server != null) {
                server.sendJson(sessionId, WebRtcSignalingResponse.of(type, payload).toJson());
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 桌面采集观察者
     */
    private class ControlDataChannelObserver implements RTCDataChannelObserver {

        @Override
        public void onBufferedAmountChange(long value) {
            // 无
        }

        @Override
        public void onStateChange() {
            // 无
        }

        @Override
        public void onMessage(RTCDataChannelBuffer buffer) {
            if (buffer.binary) {
                return;
            }
            byte[] bytes = new byte[buffer.data.remaining()];
            buffer.data.get(bytes);
            String json = new String(bytes, StandardCharsets.UTF_8);
            ControlEvent event;
            try {
                event = new DefaultControlEventFactory().parse(json);
            } catch (Exception e) {
                return;
            }
            if (event != null) {
                controlService.onControlEvent(sessionId, id, event.path(), event);
            }
        }
    }
}
