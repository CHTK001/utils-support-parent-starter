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

@Slf4j
@Spi("remote-controller")
public class ControllerClient implements RemoteControllerSPI {

    public static final String METADATA_VERIFY_CODE = "verifyCode";

    private final RemoteClient client;
    private final ControllerInfo controllerInfo;
    private volatile String currentSessionId;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final DecoderRenderer decoderRenderer;
    private final InputInjector inputInjector;
    private volatile java.util.function.Consumer<Frame> sshFrameHandler;

    public ControllerClient(String gatewayUrl, ControllerInfo controllerInfo) {
        this.client = new RemoteClient(controllerInfo.getAccessToken(), gatewayUrl);
        this.controllerInfo = controllerInfo;
        this.decoderRenderer = new DecoderRenderer(controllerInfo.getDecodingCapability());
        this.inputInjector = new InputInjector();
    }

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

    public void connect() {
        client.getTransport().on(MessageType.DATA, this::handleDataFrame);
        client.getTransport().on(MessageType.SSH, this::handleSSHFrame);
        client.connect();
        reportToGateway();
        log.info("控制端已连接: accessToken={}", controllerInfo.getAccessToken());
    }

    private void handleDataFrame(Frame frame) {
        decoderRenderer.render(frame);
    }

    private void handleSSHFrame(Frame frame) {
        if (sshFrameHandler != null) {
            sshFrameHandler.accept(frame);
        }
    }

    public void setSshFrameHandler(java.util.function.Consumer<Frame> handler) {
        this.sshFrameHandler = handler;
    }

    /** 设置渲染帧监听（画面帧 → 外部消费——如 WS 桥推流给 Web 前端） */
    public void setFrameListener(java.util.function.Consumer<java.awt.image.BufferedImage> listener) {
        decoderRenderer.setFrameListener(listener);
    }

    public void startSSHSession(String sessionId, String host, int port,
                                String username, String password, int cols, int rows) {
        Map<String, String> meta = Map.of(
                "sshAction", "start",
                "sshSessionId", sessionId,
                "host", host,
                "port", String.valueOf(port),
                "username", username,
                "password", password != null ? password : "",
                "cols", String.valueOf(cols),
                "rows", String.valueOf(rows));
        Frame frame = FrameCodec.sshFrame(sessionId, new byte[0], meta);
        client.getTransport().send(frame);
        log.info("发送SSH启动指令: sessionId={}, host={}:{}", sessionId, host, port);
    }

    public void sendSSHInput(String sessionId, byte[] data) {
        Map<String, String> meta = Map.of(
                "sshAction", "input",
                "sshSessionId", sessionId);
        Frame frame = FrameCodec.sshFrame(sessionId, data, meta);
        client.getTransport().send(frame);
    }

    public void resizeSSH(String sessionId, int cols, int rows) {
        Map<String, String> meta = Map.of(
                "sshAction", "resize",
                "sshSessionId", sessionId,
                "cols", String.valueOf(cols),
                "rows", String.valueOf(rows));
        Frame frame = FrameCodec.sshFrame(sessionId, new byte[0], meta);
        client.getTransport().send(frame);
    }

    public void stopSSHSession(String sessionId) {
        Map<String, String> meta = Map.of(
                "sshAction", "stop",
                "sshSessionId", sessionId);
        Frame frame = FrameCodec.sshFrame(sessionId, new byte[0], meta);
        client.getTransport().send(frame);
        log.info("发送SSH停止指令: sessionId={}", sessionId);
    }

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
        frame.getMetadata().put("reverseTunnelEnabled",
                String.valueOf(controllerInfo.isReverseTunnelEnabled()));
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

    public String createSession(String agentId, String verifyCode) {
        return startSession(agentId, verifyCode);
    }

    public void sessionEstablished(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.setStatus(Session.SessionStatus.ACTIVE);
            log.info("会话已建立: sessionId={}", sessionId);
        }
    }

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

    public void disconnect() {
        client.disconnect();
        sessions.clear();
        currentSessionId = null;
        log.info("控制端已断开");
    }

    public RemoteTransport getTransport() {
        return client.getTransport();
    }

    public DecoderRenderer getDecoderRenderer() {
        return decoderRenderer;
    }

    public InputInjector getInputInjector() {
        return inputInjector;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }
}
