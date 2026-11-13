package com.chua.webrtc.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebRtcServer} 信令通道真实链路用例。
 *
 * <p>客户端是裸 {@link java.net.Socket}（{@link WebRtcTestSocket}）：手工发 WebSocket Upgrade 握手、
 * 按 RFC 6455 加掩码组帧，校验 101 响应 / {@code Sec-WebSocket-Accept} / token 鉴权 /
 * 信令帧往返 / ping-pong。</p>
 *
 * <p>OFFER 与媒体协商见 {@link WebRtcOfferNegotiationTest}（需要原生 PeerConnection）。</p>
 *
 * @author CH
 */
@Timeout(30)
class WebRtcSignalingTest {

    private static WebRtcServer server;
    private static int port;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = startServer(port);
        assertTrue(server.isRunning(), "信令服务应已启动");
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * 启动一个只监听本机端口的信令服务。
     *
     * @param listenPort 端口
     * @return 服务实例
     * @throws Exception 启动失败
     */
    static WebRtcServer startServer(int listenPort) throws Exception {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(listenPort);
        setting.setProtocol("ws");
        WebRtcServer instance = new WebRtcServer(setting);
        instance.start();
        return instance;
    }

    @Test
    @DisplayName("握手：101 Switching Protocols 且 Sec-WebSocket-Accept 正确")
    void handshakeReturnsValidAccept() throws Exception {
        try (WebRtcTestSocket client = WebRtcTestSocket.connect(port, 5000)) {
            String key = WebRtcTestSocket.newKey();
            String resp = client.handshake("/webrtc", null, key);
            assertTrue(resp.startsWith("HTTP/1.1 101"), "应升级成功: " + WebRtcTestSocket.firstLine(resp));
            String expected = WebRtcTestSocket.accept(key);
            assertTrue(resp.contains("Sec-WebSocket-Accept: " + expected),
                    "Sec-WebSocket-Accept 必须等于 Base64(SHA1(key+GUID))，实际响应:\n" + resp);
        }
    }

    @Test
    @DisplayName("鉴权：token 不匹配回 403，匹配才升级")
    void tokenIsEnforced() throws Exception {
        server.setSignalingToken("s3cret");
        try {
            try (WebRtcTestSocket client = WebRtcTestSocket.connect(port, 5000)) {
                String resp = client.handshake("/webrtc", "wrong", WebRtcTestSocket.newKey());
                assertTrue(resp.startsWith("HTTP/1.1 403"),
                        "错误 token 必须被拒: " + WebRtcTestSocket.firstLine(resp));
            }
            try (WebRtcTestSocket client = WebRtcTestSocket.connect(port, 5000)) {
                String resp = client.handshake("/webrtc", "s3cret", WebRtcTestSocket.newKey());
                assertTrue(resp.startsWith("HTTP/1.1 101"),
                        "正确 token 应升级: " + WebRtcTestSocket.firstLine(resp));
            }
        } finally {
            server.setSignalingToken(null);
        }
    }

    @Test
    @DisplayName("信令：answer 帧得到 ok 响应帧")
    void answerGetsOkResponse() throws Exception {
        try (WebRtcTestSocket client = WebRtcTestSocket.connect(port, 5000)) {
            client.handshake("/webrtc", null, WebRtcTestSocket.newKey());
            client.writeText("{\"peerId\":\"p1\",\"type\":\"answer\",\"payload\":\"{}\"}");
            String resp = client.readText();
            assertNotNull(resp, "应收到信令响应帧");
            assertTrue(resp.contains("\"ok\""), "answer 应回 ok，实际: " + resp);
        }
    }

    @Test
    @DisplayName("信令：同一连接可连续处理多帧（ping/pong 后仍能收信令响应）")
    void multipleFramesOnOneConnection() throws Exception {
        try (WebRtcTestSocket client = WebRtcTestSocket.connect(port, 5000)) {
            client.handshake("/webrtc", null, WebRtcTestSocket.newKey());
            // 控制帧：ping → pong（opcode 0xA）
            client.writeFrame(0x9, "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            WebRtcTestSocket.Frame pong = client.readFrame();
            assertEquals(0xA, pong.opcode, "ping 应回 pong");
            assertEquals("hi", new String(pong.payload, java.nio.charset.StandardCharsets.UTF_8));

            for (int i = 1; i <= 3; i++) {
                client.writeText("{\"peerId\":\"p1\",\"type\":\"answer\",\"payload\":\"{}\"}");
                String resp = client.readText();
                assertNotNull(resp, "第 " + i + " 帧未收到响应");
                assertTrue(resp.contains("\"ok\""), "第 " + i + " 帧响应异常: " + resp);
            }
        }
    }

    @Test
    @DisplayName("非法握手：非 Upgrade 请求直接断开")
    void nonUpgradeRequestIsClosed() throws Exception {
        try (WebRtcTestSocket client = WebRtcTestSocket.connect(port, 5000)) {
            client.writeRaw("GET /webrtc HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertEquals(-1, client.readByte(), "非 Upgrade 请求应被服务端关闭");
        }
    }
}
