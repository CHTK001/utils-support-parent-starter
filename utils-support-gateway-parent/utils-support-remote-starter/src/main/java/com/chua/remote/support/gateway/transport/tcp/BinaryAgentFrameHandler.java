package com.chua.remote.support.gateway.transport.tcp;

import com.chua.remote.support.gateway.core.session.GatewaySession;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.transport.codec.H264ToJpegTranscoder;
import com.chua.remote.support.gateway.transport.ws.ClientCapabilityManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Agent 二进制帧处理器
 * <p>
 * 处理 Agent→Gateway 的桌面二进制帧（H264 / JPEG 编码），
 * 根据客户端能力管理器记录的编解码能力决策：
 * <ul>
 *   <li>客户端支持 Agent 编码 → 直传（不转码）</li>
 *   <li>客户端尚未声明能力 → 默认放行 H264（竞态保护）</li>
 *   <li>Agent H264 且客户端仅支持 JPEG → 通过 H264ToJpegTranscoder 实时转码</li>
 *   <li>完全不匹配 → 记录警告日志并丢弃</li>
 * </ul>
 *
 * @author CH
 * @see ClientCapabilityManager 客户端能力查询
 * @see H264ToJpegTranscoder H264→JPEG 转码器
 */
@Slf4j
public class BinaryAgentFrameHandler extends SimpleChannelInboundHandler<BinaryFrame> {

    /** 会话管理器，用于查找会话对应的 WS 客户端通道 */
    private final SessionManager sessionManager;
    /** 客户端能力管理器，用于查询各会话的编解码器支持情况 */
    private final ClientCapabilityManager capabilityManager;
    /** H264→JPEG 转码器 */
    private final H264ToJpegTranscoder jpegTranscoder;

    /**
     * 构造二进制帧处理器
     *
     * @param sessionManager    会话管理器
     * @param capabilityManager 客户端能力管理器
     */
    public BinaryAgentFrameHandler(SessionManager sessionManager,
                                    ClientCapabilityManager capabilityManager) {
        this.sessionManager = sessionManager;
        this.capabilityManager = capabilityManager;
        this.jpegTranscoder = new H264ToJpegTranscoder();
    }

    /**
     * 处理 Agent 发来的桌面二进制帧。
     * <p>
     * 根据客户端能力管理器决策处理方式：
     * <ol>
     *   <li>客户端支持该编码 → 直传 WS 客户端</li>
     *   <li>客户端尚未发送能力声明 → 默认放行（竞态保护）</li>
     *   <li>Agent H264 且客户端仅支持 JPEG → H264ToJpegTranscoder 实时转码</li>
     *   <li>编码不匹配 → 记录警告并丢弃</li>
     * </ol>
     *
     * @param ctx   通道处理器上下文
     * @param frame Agent 二进制帧对象
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryFrame frame) {
        try {
            String sessionId = frame.getSessionId();
            byte frameType = frame.getFrameType();
            int width = frame.getWidth();
            int height = frame.getHeight();
            boolean keyFrame = frame.isKeyFrame();
            byte[] encodedData = frame.getEncodedData();

            log.debug("[BinaryAgentFrame] 收到帧: sid={} type=0x{} {}x{} key={} dataLen={}",
                    sessionId, String.format("%02X", frameType), width, height, keyFrame, encodedData.length);

            // 查找会话
            GatewaySession session = sessionManager.getSession(sessionId);
            if (session == null) {
                log.warn("[BinaryAgentFrame] session 不存在: {}", sessionId);
                return;
            }

            Channel wsChannel = session.getClientChannel();
            if (wsChannel == null || !wsChannel.isActive()) {
                log.warn("[BinaryAgentFrame] WS 客户端已断开: sessionId={}", sessionId);
                return;
            }

            // 客户端能力匹配
            String agentCodec = frameType == BinaryFrame.TYPE_H264 ? "H264" : "JPEG";

            if (capabilityManager.supportsCodec(sessionId, agentCodec)) {
                // 匹配 → 直传
                sendToClient(wsChannel, session, sessionId, frameType, width, height, keyFrame, encodedData);
            } else if (capabilityManager.getCapability(sessionId) == null) {
                // 客户端尚未发送 capabilities（刚连接的竞态），默认放行 H264
                sendToClient(wsChannel, session, sessionId, frameType, width, height, keyFrame, encodedData);
            } else if ("H264".equals(agentCodec) && capabilityManager.supportsCodec(sessionId, "JPEG")) {
                // 不匹配: Agent H264, 客户端只支持 JPEG → 转码
                log.info("[BinaryAgentFrame] 转码 H264→JPEG: sessionId={} {}x{} len={}",
                        sessionId, width, height, encodedData.length);
                byte[] jpegData = jpegTranscoder.transcode(encodedData, width, height);
                if (jpegData != null) {
                    sendToClient(wsChannel, session, sessionId, BinaryFrame.TYPE_JPEG, width, height, true, jpegData);
                    log.info("[BinaryAgentFrame] 转码完成: {}→{} bytes", encodedData.length, jpegData.length);
                }
 else {
                    log.warn("[BinaryAgentFrame] 转码失败, sessionId={}", sessionId);
                }
            }
 else {
                log.warn("[BinaryAgentFrame] 客户端不支持的编码: sessionId={} agent={} clientCap={}",
                        sessionId, agentCodec, capabilityManager.getCapability(sessionId));
            }
        }
 catch (Exception e) {
            log.warn("[BinaryAgentFrame] 处理失败: {}", e.getMessage());
        }
    }

    /**
     * 向 WS 客户端发送帧
     * WS协议: [4B sidLen][sid...][4B w][4B h][1B keyFrame][encoded data]
     */
    private void sendToClient(Channel wsChannel, GatewaySession session, String sessionId, byte frameType,
                               int width, int height, boolean keyFrame, byte[] data) {
        if (wsChannel == null || !wsChannel.isActive()) { return; }

        byte[] sidBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(13 + data.length);
        buf.writeInt(sidBytes.length);
        buf.writeBytes(sidBytes);
        buf.writeInt(width);
        buf.writeInt(height);
        buf.writeBoolean(keyFrame);
        buf.writeBytes(data);

        wsChannel.writeAndFlush(new BinaryWebSocketFrame(buf));
        session.addFrameSent(data.length);
        log.debug("[BinaryAgentFrame] → WS: sid={} {}x{} type={} len={}",
                sessionId, width, height, String.format("0x%02X", frameType), data.length);
    }
}
