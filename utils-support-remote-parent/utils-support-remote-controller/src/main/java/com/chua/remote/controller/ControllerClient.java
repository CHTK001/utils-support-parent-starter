package com.chua.remote.controller;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteControllerSPI;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制端客户端。
 *
 * <p>以接入令牌为连接标识接入网关、上报解码能力、发起会话请求、
 * 接收并渲染屏幕流、注入键鼠事件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("remote-controller")
public class ControllerClient implements RemoteControllerSPI {

    /** 会话请求帧元数据键：被控端验证码 */
    public static final String METADATA_VERIFY_CODE = "verifyCode";

    /** 网关客户端（以接入令牌为连接标识，供网关定向路由） */
    private final RemoteClient client;

    /** 控制端信息 */
    private final ControllerInfo controllerInfo;

    /** 当前会话 id */
    private volatile String currentSessionId;

    /** 会话映射 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 解码渲染器 */
    private final DecoderRenderer decoderRenderer;

    /** 键鼠事件注入器 */
    private final InputInjector inputInjector;

    public ControllerClient(String gatewayUrl, ControllerInfo controllerInfo) {
        this.client = new RemoteClient(controllerInfo.getAccessToken(), gatewayUrl);
        this.controllerInfo = controllerInfo;
        this.decoderRenderer = new DecoderRenderer(controllerInfo.getDecodingCapability());
        this.inputInjector = new InputInjector();
    }

    /**
     * HTTP 验证（点击连接前调用）。
     *
     * @param gatewayHttpUrl 网关 HTTP 地址，如 http://localhost:9001
     * @param agentId        被控端 id
     * @param verifyCode     验证码
     * @return 验证结果 JSON
     */
    public static VerifyResult verify(String gatewayHttpUrl, String agentId, String verifyCode) {
        try {
            String body = "agentId=" + java.net.URLEncoder.encode(agentId, StandardCharsets.UTF_8)
                    + "&verifyCode=" + java.net.URLEncoder.encode(verifyCode, StandardCharsets.UTF_8);
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayHttpUrl + "/verify"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new VerifyResult(resp.statusCode(), resp.body());
        } catch (Exception e) {
            return new VerifyResult(500, "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    public record VerifyResult(int statusCode, String body) {
        public boolean isSuccess() {
            return statusCode == 200 && body.contains("\"success\":true");
        }
    }

    /**
     * 连接到网关并订阅屏幕流数据帧。
     */
    public void connect() {
        client.getTransport().on(MessageType.DATA, this::handleDataFrame);
        client.connect();
        // 上报接入令牌和解码能力
        reportToGateway();
        log.info("控制端已连接: accessToken={}", controllerInfo.getAccessToken());
    }

    /**
     * 处理网关转发的数据帧（屏幕流），解码渲染。
     *
     * @param frame 数据帧
     */
    private void handleDataFrame(Frame frame) {
        decoderRenderer.render(frame);
    }

    /** 上报到网关 */
    private void reportToGateway() {
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, controllerInfo.getAccessToken(), controllerInfo);
        client.getTransport().send(frame);
    }

    @Override
    public String connect(ControllerInfo info) {
        client.connect();
        reportToGateway();
        return info.getAccessToken();
    }

    /**
     * 发起会话（控制端本地登记并向网关发送会话请求信令）。
     *
     * <p>会话 id 由控制端预生成，随信令帧上报网关；
     * 信令元数据携带被控端验证码，供网关校验会话建立资格。</p>
     *
     * @param agentId    被控端 id
     * @param verifyCode 被控端验证码
     * @return 会话 id
     */
    @Override
    public String startSession(String agentId, String verifyCode) {
        String sessionId = UUID.randomUUID().toString();
        var session = Session.builder()
                .sessionId(sessionId)
                .controllerSessionId(controllerInfo.getAccessToken())
                .agentId(agentId)
                .status(Session.SessionStatus.CONNECTING)
                .createTime(System.currentTimeMillis())
                .build();
        sessions.put(sessionId, session);
        currentSessionId = sessionId;
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, sessionId, session);
        frame.getMetadata().put(METADATA_VERIFY_CODE, verifyCode);
        client.getTransport().send(frame);
        log.info("发起会话: controllerId={}, agentId={}, sessionId={}",
                controllerInfo.getAccessToken(), agentId, sessionId);
        return sessionId;
    }

    @Override
    public void reportCapability(CodecProfile capability) {
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, currentSessionId, capability);
        client.getTransport().send(frame);
        log.info("上报解码能力: sessionId={}", currentSessionId);
    }

    @Override
    public void renderFrame(byte[] frameData) {
        var frame = FrameCodec.dataFrame(currentSessionId, frameData);
        decoderRenderer.render(frame);
        log.debug("渲染画面帧: sessionId={}, size={}", currentSessionId, frameData.length);
    }

    @Override
    public void injectInputEvent(byte[] eventData) {
        Session session = currentSessionId != null ? sessions.get(currentSessionId) : null;
        if (session != null && session.getAgentType() == com.chua.remote.protocol.model.AgentInfo.AgentType.PUSH) {
            log.warn("被控端为纯推送模式（PUSH），不支持远程控制，丢弃键鼠事件: sessionId={}", currentSessionId);
            return;
        }
        var frame = FrameCodec.controlFrame(currentSessionId, eventData);
        client.getTransport().send(frame);
        inputInjector.inject(eventData);
        log.debug("注入键鼠事件: sessionId={}", currentSessionId);
    }

    /**
     * 发起会话并返回会话 id。
     *
     * @param agentId    被控端 id
     * @param verifyCode 验证码
     * @return 会话 id
     */
    public String createSession(String agentId, String verifyCode) {
        return startSession(agentId, verifyCode);
    }

    /**
     * 标记会话已建立（网关确认后回调）。
     *
     * @param sessionId 会话 id
     */
    public void sessionEstablished(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.setStatus(Session.SessionStatus.ACTIVE);
            log.info("会话已建立: sessionId={}", sessionId);
        }
    }

    /**
     * 关闭会话并通知网关。
     *
     * @param sessionId 会话 id
     */
    public void closeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        session.setStatus(Session.SessionStatus.CLOSED);
        var frame = Frame.builder()
                .type(MessageType.CTRL)
                .sessionId(sessionId)
                .metadata(Map.of(FrameCodec.METADATA_KIND, "SessionClose"))
                .build();
        client.getTransport().send(frame);
        if (sessionId.equals(currentSessionId)) {
            currentSessionId = null;
        }
        log.info("会话已关闭: sessionId={}", sessionId);
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        client.disconnect();
        sessions.clear();
        currentSessionId = null;
        log.info("控制端已断开");
    }

    /**
     * 获取底层传输层。
     *
     * <p>供嵌入式部署订阅帧或观测传输使用。</p>
     *
     * @return 传输层
     */
    public RemoteTransport getTransport() {
        return client.getTransport();
    }

    /**
     * 获取解码渲染器。
     *
     * @return DecoderRenderer
     */
    public DecoderRenderer getDecoderRenderer() {
        return decoderRenderer;
    }

    /**
     * 获取键鼠事件注入器。
     *
     * @return InputInjector
     */
    public InputInjector getInputInjector() {
        return inputInjector;
    }

    /**
     * 获取当前会话 id。
     *
     * @return 会话 id
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }
}
