package com.chua.remote.controller;

import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * 远控核心与控制端链路冒烟测试。
 *
 * <p>验证真实 TCP 双向链路：线格式编解码（二进制 Base64 承载）、
 * 客户端身份注册、信令上报、定向/广播数据帧、解码渲染、会话请求与键鼠控制信令。</p>
 *
 * <p>main() 入口：退出码 0 表示全部通过，1 表示存在失败项。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RemoteCoreControllerSmokeTest {

    /** 冒烟服务端口 */
    private static final int PORT = 28391;

    /** 失败计数 */
    private static int failures = 0;

    /** 检查项计数 */
    private static int checks = 0;

    /**
     * 冒烟入口。
     *
     * @param args 未使用
     * @throws Exception 链路异常
     */
    public static void main(String[] args) throws Exception {
        wireRoundTrip();
        tcpFullChain();
        if (failures > 0) {
            System.err.println("SMOKE FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("SMOKE PASSED: " + checks + " checks");
    }

    /**
     * 记录检查项。
     *
     * @param name 检查项名称
     * @param cond 是否通过
     */
    static void check(String name, boolean cond) {
        checks++;
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + name);
        if (!cond) {
            failures++;
        }
    }

    /**
     * 轮询等待条件成立。
     *
     * @param cond      条件
     * @param timeoutMs 超时（毫秒）
     * @return 条件是否成立
     * @throws InterruptedException 中断
     */
    static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    /**
     * 生成真实 JPEG 屏幕帧。
     *
     * @return JPEG 字节
     * @throws Exception 图像编码异常
     */
    static byte[] jpegFrame() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 32, 32);
        graphics.dispose();
        byte[] png = BufferedImageUtils.toBufferedImageArray(image, "png");
        return ImageProcessors.from(png).format("jpeg").toBytes();
    }

    /**
     * 构造被控端信息。
     *
     * @return 被控端信息
     */
    static AgentInfo makeAgentInfo() {
        return AgentInfo.builder().id("agent-9").verifyCode("vc").accessCode("ac").build();
    }

    /**
     * 场景一：线格式编解码纯逻辑校验。
     */
    static void wireRoundTrip() {
        byte[] payload = {0, 1, 2, -1, 127, -128, 9};
        Frame decoded = FrameCodec.decodeWire(FrameCodec.encodeWire(FrameCodec.dataFrame("s-1", payload)));
        check("wire: 二进制载荷往返一致", decoded != null
                && decoded.getType() == MessageType.DATA
                && "s-1".equals(decoded.getSessionId())
                && Arrays.equals(payload, decoded.getPayload()));
        check("wire: 非法输入返回 null", FrameCodec.decodeWire("not-json") == null);

        Frame signal = FrameCodec.encodeSignal(MessageType.SIGNAL, "s-2", makeAgentInfo());
        check("signal: kind 元数据自动标识", signal.getMetadata() != null
                && "AgentInfo".equals(signal.getMetadata().get(FrameCodec.METADATA_KIND)));
        AgentInfo back = FrameCodec.decodeSignal(signal, AgentInfo.class);
        check("signal: AgentInfo JSON 往返一致", back != null && "agent-9".equals(back.getId()));
    }

    /**
     * 场景二：真实 TCP 双向链路（服务端 + 控制端客户端）。
     *
     * @throws Exception 链路异常
     */
    static void tcpFullChain() throws Exception {
        ServerSetting setting = ServerSetting.builder()
                .auto(false).host("127.0.0.1").port(PORT).build();
        RemoteServer server = new RemoteServer(setting);
        List<Frame> serverSignals = new CopyOnWriteArrayList<>();
        List<Frame> serverCtrl = new CopyOnWriteArrayList<>();
        server.getTransport().on(MessageType.SIGNAL, serverSignals::add);
        server.getTransport().on(MessageType.CTRL, serverCtrl::add);
        server.start();
        try {
            ControllerInfo controllerInfo = ControllerInfo.builder()
                    .accessToken("ctrl-token-1")
                    .decodingCapability(CodecProfile.builder()
                            .encodings(List.of("JPEG"))
                            .maxWidth(640)
                            .maxHeight(480)
                            .quality(80)
                            .build())
                    .targetAgentId("agent-9")
                    .build();
            ControllerClient controller = new ControllerClient("tcp://127.0.0.1:" + PORT, controllerInfo);

            List<Frame> clientData = new CopyOnWriteArrayList<>();
            controller.getTransport().on(MessageType.DATA, clientData::add);
            controller.connect();

            // 1. 控制端以接入令牌为身份注册，ControllerInfo 信令到达服务端
            boolean registered = await(() -> !serverSignals.isEmpty(), 5000);
            check("tcp: 控制端 ControllerInfo 信令到达", registered
                    && serverSignals.get(0).getMetadata() != null
                    && "ControllerInfo".equals(serverSignals.get(0).getMetadata().get(FrameCodec.METADATA_KIND)));

            // 2. 服务端定向发送 JPEG 数据帧 → 控制端接收且二进制一致（Base64 线格式）
            byte[] jpeg = jpegFrame();
            server.getTransport().send("ctrl-token-1", FrameCodec.dataFrame("sess-1", jpeg));
            boolean dataArrived = await(() -> !clientData.isEmpty(), 5000);
            check("tcp: 定向数据帧到达且二进制一致", dataArrived
                    && Arrays.equals(jpeg, clientData.get(0).getPayload()));

            // 3. 服务端广播数据帧 → 控制端接收
            server.getTransport().publish(FrameCodec.dataFrame("sess-1", jpeg));
            check("tcp: 广播数据帧到达", await(() -> clientData.size() >= 2, 5000));

            // 4. 控制端发起会话：Session 信令 + verifyCode 元数据
            String sessionId = controller.startSession("agent-9", "123456");
            boolean sessionRequested = await(() -> serverSignals.size() >= 2, 5000);
            Frame sessionFrame = sessionRequested ? serverSignals.get(serverSignals.size() - 1) : null;
            check("tcp: 会话请求信令携带 Session/verifyCode", sessionFrame != null
                    && sessionId.equals(sessionFrame.getSessionId())
                    && sessionFrame.getMetadata() != null
                    && "123456".equals(sessionFrame.getMetadata().get(ControllerClient.METADATA_VERIFY_CODE)));

            // 5. 键鼠事件 CTRL 帧到达服务端
            byte[] inputEvent = {1, 2, 3};
            controller.injectInputEvent(inputEvent);
            check("tcp: 键鼠控制帧到达且载荷一致", await(() -> !serverCtrl.isEmpty(), 5000)
                    && Arrays.equals(inputEvent, serverCtrl.get(0).getPayload()));

            // 6. 关闭会话信令
            controller.closeSession(sessionId);
            check("tcp: 会话关闭信令到达", await(() -> serverCtrl.size() >= 2, 5000));

            controller.disconnect();
        } finally {
            server.stop();
        }
    }
}
