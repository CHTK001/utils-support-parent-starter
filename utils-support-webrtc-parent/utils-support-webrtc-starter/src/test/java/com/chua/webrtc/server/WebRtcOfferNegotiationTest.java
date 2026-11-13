package com.chua.webrtc.server;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link WebRtcServer} 的 OFFER/answer 真实协商用例。
 *
 * <p>客户端是 <b>webrtc-java 原生 PeerConnection</b>：真实生成 offer（video recvonly 收发
 * 方向，与浏览器 <code>recvonly</code> 口径一致），经 WebSocket 信令发给服务端，
 * 服务端用原生 PeerConnection {@code setRemoteDescription} + {@code createAnswer}
 * 回 answer，客户端再 {@code setRemoteDescription(answer)}。</p>
 *
 * <p>不建 ICE 通道、不断言媒体连通（本机 headless 无采集源）；只验证 SDP 协商链路
 * 与 answer 的合法性。若原生库不可用，用例会直接失败而不是被跳过——矩阵要求「未验证的
 * 不得标为支持」。</p>
 *
 * @author CH
 */
@Timeout(60)
class WebRtcOfferNegotiationTest {

    private static WebRtcServer server;
    private static int port;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = WebRtcSignalingTest.startServer(port);
        assertTrue(server.isRunning(), "信令服务应已启动");
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("OFFER/answer：原生 PeerConnection 完成真实 SDP 往返")
    void offerGetsAnswerOverSignaling() throws Exception {
        PeerConnectionFactory factory = new PeerConnectionFactory();
        RTCPeerConnection pc = null;
        try (WebRtcTestSocket ws = WebRtcTestSocket.connect(port, 20000)) {
            String resp = ws.handshake("/webrtc", null, WebRtcTestSocket.newKey());
            assertTrue(resp.startsWith("HTTP/1.1 101"), "信令握手失败: " + WebRtcTestSocket.firstLine(resp));

            // 首个帧上报 peerId，服务端据此注册回信地址（并回一个 ok）
            ws.writeText(Json.createJsonObject().fluentPut("peerId", "p1").toJSONString());
            String ack = ws.readText();
            assertNotNull(ack, "首帧应得到 ok 响应");

            pc = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    // 本用例只验 SDP 往返，不建媒体通道
                }
            });
            pc.createDataChannel("control", new dev.onvoid.webrtc.RTCDataChannelInit());

            String offer = createOffer(pc);
            assertTrue(offer.contains("m=application"), "客户端 offer 应带 data channel m-line: " + brief(offer));
            setLocalDescription(pc, RTCSdpType.OFFER, offer);
            String offerJson = Json.createJsonObject()
                    .fluentPut("peerId", "p1")
                    .fluentPut("type", "offer")
                    .fluentPut("payload", offer)
                    .toJSONString();
            ws.writeText(offerJson);

            String answer = waitForAnswer(ws, 15_000L);
            assertNotNull(answer, "15s 内未收到 answer 帧（服务端 OFFER 分支无响应）");

            setRemoteDescription(pc, RTCSdpType.ANSWER, answer);
        } finally {
            if (pc != null) {
                pc.close();
            }
            factory.dispose();
        }
    }

    /**
     * 客户端创建 offer（无 ICE 收集等待，只取 SDP）。
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
     * @param pc      PeerConnection
     * @param type    SDP 类型
     * @param sdp     SDP 文本
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

    /**
     * 从信令连接上等待 answer 帧（跳过 ok 与 ICE candidate 帧）。
     *
     * @param ws        信令连接
     * @param timeoutMs 超时
     * @return answer SDP；超时返回 null
     * @throws Exception 读取失败
     */
    private static String waitForAnswer(WebRtcTestSocket ws, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String text = ws.readText();
            if (text == null) {
                return null;
            }
            JsonObject json = Json.fromJson(text, JsonObject.class);
            String type = json == null ? null : String.valueOf(json.get("type"));
            if ("answer".equals(type)) {
                return String.valueOf(json.get("payload"));
            }
            if (!"ok".equals(type) && !"ice-candidate".equals(type)) {
                return null;
            }
        }
        return null;
    }

    /**
     * 取 SDP 摘要（断言失败信息用）。
     *
     * @param sdp SDP
     * @return 首行与 m= 行
     */
    private static String brief(String sdp) {
        StringBuilder sb = new StringBuilder();
        for (String line : sdp.split("\r\n")) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(line);
            if (sb.length() > 300) {
                break;
            }
        }
        return sb.toString();
    }
}
