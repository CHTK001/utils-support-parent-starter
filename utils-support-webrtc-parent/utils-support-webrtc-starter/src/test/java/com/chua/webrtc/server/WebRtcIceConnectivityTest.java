package com.chua.webrtc.server;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.webrtc.model.WebRtcPeer;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link WebRtcServer} 的媒体通道连通用例：真实 ICE + DTLS + SCTP。
 *
 * <p>与 {@link WebRtcOfferNegotiationTest}（只验 SDP 往返）互补：这里把 ICE 候选
 * 双向交换完，等原生 PeerConnection 真正建立传输通道，再以 <b>data channel 打开</b>
 * 作为连通判据。data channel 走的是 SCTP over DTLS，DTLS 又必须建立在 ICE 选出的
 * 候选对上，所以「channel 打开」等价于「ICE 连通 + DTLS 握手完成 + SCTP 关联建立」，
 * 这三层任何一层断了用例都会红。</p>
 *
 * <p>不覆盖 video 帧：headless 环境没有可用采集源（`VideoDesktopSource` 需要真实桌面），
 * 因此「屏幕共享出图」仍不得据此断言。</p>
 *
 * @author CH
 */
@Timeout(180)
class WebRtcIceConnectivityTest {

    /**
     * 信令服务端。
     */
    private static WebRtcServer server;

    /**
     * 信令端口。
     */
    private static int port;

    /**
     * 启动信令服务端。
     *
     * @throws Exception 启动失败
     */
    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = WebRtcSignalingTest.startServer(port);
        assertTrue(server.isRunning(), "信令服务应已启动");
    }

    /**
     * 停止信令服务端。
     */
    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("ICE + DTLS + SCTP 连通：data channel 打开且服务端 peer 进入 CONNECTED")
    void dataChannelConnectsOverRealIce() throws Exception {
        PeerConnectionFactory factory = new PeerConnectionFactory();
        RTCPeerConnection pc = null;
        Thread reader = null;
        AtomicReference<RTCPeerConnection> pcRef = new AtomicReference<>();
        try (WebRtcTestSocket ws = WebRtcTestSocket.connect(port, 30000)) {
            assertTrue(ws.handshake("/webrtc", null, WebRtcTestSocket.newKey()).startsWith("HTTP/1.1 101"),
                    "信令握手失败");

            // 首个帧上报 peerId，服务端据此注册回信地址（并回一个 ok）
            ws.writeText(Json.createJsonObject().fluentPut("peerId", "ice-1").toJSONString());
            assertNotNull(ws.readText(), "首帧应得到 ok 响应");

            // 信令帧是双向并发的（ICE 回调线程 + 主线程），必须串行化写入，否则帧会交错
            Object writeLock = new Object();
            CountDownLatch open = new CountDownLatch(1);
            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch answerArrived = new CountDownLatch(1);
            AtomicInteger localCandidates = new AtomicInteger();
            AtomicInteger remoteCandidates = new AtomicInteger();
            AtomicReference<String> answerRef = new AtomicReference<>();
            AtomicReference<Throwable> writeFailure = new AtomicReference<>();
            ConcurrentLinkedQueue<RTCIceCandidate> remoteQueue = new ConcurrentLinkedQueue<>();

            PeerConnectionObserver observer = new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    localCandidates.incrementAndGet();
                    String payload = Json.createJsonObject()
                            .fluentPut("sdpMid", candidate.sdpMid)
                            .fluentPut("sdpMLineIndex", candidate.sdpMLineIndex)
                            .fluentPut("sdp", candidate.sdp)
                            .toJSONString();
                    String frame = Json.createJsonObject()
                            .fluentPut("peerId", "ice-1")
                            .fluentPut("type", "ice-candidate")
                            .fluentPut("payload", payload)
                            .toJSONString();
                    try {
                        synchronized (writeLock) {
                            ws.writeText(frame);
                        }
                    } catch (Exception e) {
                        writeFailure.compareAndSet(null, e);
                    }
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    if (state == RTCPeerConnectionState.CONNECTED) {
                        connected.countDown();
                    }
                }

                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    // 服务端自己建的 control 通道会在客户端出现；本用例只等客户端那条打开
                }
            };

            pc = factory.createPeerConnection(new RTCConfiguration(), observer);
            pcRef.set(pc);

            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            RTCDataChannel channel = pc.createDataChannel("control", init);
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long amount) {
                    // 无需处理
                }

                @Override
                public void onStateChange() {
                    if (channel.getState() == RTCDataChannelState.OPEN) {
                        open.countDown();
                    }
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    // 服务端不主动发控制事件
                }
            });

            // 信令读线程：answer 到达即可设置远端描述；ICE 候选先入队，由主线程在
            // setRemoteDescription 之后逐个 addIceCandidate（顺序不能反，否则被原生层拒绝）
            reader = new Thread(() -> {
                try {
                    while (true) {
                        String text = ws.readText();
                        if (text == null) {
                            return;
                        }
                        JsonObject json = Json.fromJson(text, JsonObject.class);
                        if (json == null) {
                            continue;
                        }
                        String type = String.valueOf(json.get("type"));
                        if ("answer".equals(type)) {
                            answerRef.set(String.valueOf(json.get("payload")));
                            answerArrived.countDown();
                        } else if ("ice-candidate".equals(type)) {
                            JsonObject candidate = Json.fromJson(String.valueOf(json.get("payload")), JsonObject.class);
                            if (candidate == null) {
                                continue;
                            }
                            remoteQueue.add(new RTCIceCandidate(
                                    candidate.getType("sdpMid", "0", String.class),
                                    candidate.getType("sdpMLineIndex", 0, Integer.class),
                                    candidate.getType("sdp", "", String.class)));
                            remoteCandidates.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // 连接关闭或读超时：用例结束
                }
            }, "webrtc-signal-reader");
            reader.setDaemon(true);
            reader.start();

            String offer = createOffer(pc);
            assertTrue(offer.contains("m=application"), "客户端 offer 应带 data channel m-line");
            setLocalDescription(pc, RTCSdpType.OFFER, offer);
            String offerJson = Json.createJsonObject()
                    .fluentPut("peerId", "ice-1")
                    .fluentPut("type", "offer")
                    .fluentPut("payload", offer)
                    .toJSONString();
            synchronized (writeLock) {
                ws.writeText(offerJson);
            }

            assertTrue(answerArrived.await(30, TimeUnit.SECONDS), "30s 内未收到 answer 帧");
            assertNull(writeFailure.get(), "ICE 候选回写失败: " + writeFailure.get());
            setRemoteDescription(pc, RTCSdpType.ANSWER, answerRef.get());

            // 主线程按到达顺序补加远端候选，直到 data channel 打开
            long deadline = System.currentTimeMillis() + 60_000L;
            while (!open.await(200, TimeUnit.MILLISECONDS)) {
                RTCIceCandidate candidate;
                while ((candidate = remoteQueue.poll()) != null) {
                    pc.addIceCandidate(candidate);
                }
                if (System.currentTimeMillis() > deadline) {
                    fail("60s 内 data channel 未打开（ICE=" + localCandidates.get()
                            + "/" + remoteCandidates.get() + " 候选，peer=" + server.peers("ice-1") + "）");
                }
            }

            assertTrue(connected.await(10, TimeUnit.SECONDS),
                    "data channel 已打开但 PeerConnection 未进入 CONNECTED");
            assertTrue(localCandidates.get() > 0, "客户端未产生 ICE 候选");
            assertTrue(remoteCandidates.get() > 0,
                    "服务端未回 ICE 候选（远端候选数为 0，说明只做了 SDP 往返）");
            assertTrue(server.peers("ice-1").stream()
                            .anyMatch(peer -> peer.getState() == WebRtcPeer.ConnectionState.CONNECTED),
                    "服务端 peer 未进入 CONNECTED，实际状态 "
                            + server.peers("ice-1").stream().map(WebRtcPeer::getState).toList());

            // [P3C E1 豁免] 连通性证据
            System.out.println("[webrtc-ice] dataChannel=OPEN localCandidates=" + localCandidates.get()
                    + " remoteCandidates=" + remoteCandidates.get());
            System.out.flush();
        } finally {
            if (reader != null) {
                reader.interrupt();
            }
            RTCPeerConnection connection = pcRef.get();
            if (connection != null) {
                connection.close();
            }
            factory.dispose();
        }
    }

    /**
     * 客户端创建 offer（只取 SDP，不等 ICE 收集结束）。
     *
     * @param pc PeerConnection
     * @return offer SDP
     * @throws Exception 创建失败或超时
     */
    private static String createOffer(RTCPeerConnection pc) throws Exception {
        AtomicReference<String> sdp = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                sdp.set(description.sdp);
                latch.countDown();
            }

            @Override
            public void onFailure(String message) {
                failure.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS), "createOffer 超时");
        assertNotNull(sdp.get(), "createOffer 失败: " + failure.get());
        return sdp.get();
    }

    /**
     * 设置本地描述并等待完成。
     *
     * @param pc   PeerConnection
     * @param type SDP 类型
     * @param sdp  SDP 文本
     * @throws Exception 设置失败或超时
     */
    private static void setLocalDescription(RTCPeerConnection pc, RTCSdpType type, String sdp) throws Exception {
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        pc.setLocalDescription(new RTCSessionDescription(type, sdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(String message) {
                failure.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS), "setLocalDescription 超时");
        if (failure.get() != null) {
            fail("setLocalDescription(offer) 被拒绝: " + failure.get());
        }
    }

    /**
     * 设置远端描述并等待完成。
     *
     * @param pc   PeerConnection
     * @param type SDP 类型
     * @param sdp  SDP 文本
     * @throws Exception 设置失败或超时
     */
    private static void setRemoteDescription(RTCPeerConnection pc, RTCSdpType type, String sdp) throws Exception {
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        pc.setRemoteDescription(new RTCSessionDescription(type, sdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }

            @Override
            public void onFailure(String message) {
                failure.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS), "setRemoteDescription 超时");
        if (failure.get() != null) {
            fail("setRemoteDescription(answer) 被拒绝: " + failure.get());
        }
    }
}
