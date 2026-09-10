package com.chua.remote.agent.vnc;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.model.AgentInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VNC 桌面会话通道（标准 RFB 协议）。
 *
 * <p>通过 {@link RfbClient} 连接本机 VNC server（winvnc4/Xvnc），
 * 按 RFB 协议获取 framebuffer 画面并推送到网关，键鼠事件通过 RFB 协议注入 VNC server。</p>
 */
@Slf4j
public final class VncSessionChannel {

    private static final String META_ACTION = "vncAction";
    private static final String META_SESSION_ID = "vncSessionId";
    private static final String ACTION_START = "start";
    private static final String ACTION_INPUT = "input";
    private static final String ACTION_STOP = "stop";
    private static final int DEFAULT_FPS = 15;
    private static final String VNC_HOST = "127.0.0.1";
    private static final int VNC_PORT = 5900;

    private final RemoteClient client;
    private final String agentId;
    private final VncServiceManager serviceManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RfbSession> sessions = new ConcurrentHashMap<>();

    public VncSessionChannel(RemoteClient client, AgentInfo agentInfo, VncServiceManager serviceManager) {
        this.client = client;
        this.agentId = agentInfo.getId();
        this.serviceManager = serviceManager;
    }

    public void handleVncFrame(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) return;
        String action = meta.getOrDefault(META_ACTION, "");
        String sessionId = meta.getOrDefault(META_SESSION_ID, "");
        switch (action) {
            case ACTION_START:
                startSession(sessionId);
                break;
            case ACTION_INPUT:
                handleInput(sessionId, frame.getPayload(), meta);
                break;
            case ACTION_STOP:
                stopSession(sessionId);
                break;
        }
    }

    private void startSession(String sessionId) {
        if (!serviceManager.ensureVncService()) {
            sendVncFrame(sessionId, "error", "VNC 服务不可用".getBytes());
            return;
        }
        sessions.computeIfAbsent(sessionId, id -> {
            RfbSession rfb = new RfbSession(id);
            executor.submit(rfb);
            sendVncFrame(id, "started", null);
            log.info("VNC 会话启动: sessionId={}, VNC服务=127.0.0.1:{}", id, VNC_PORT);
            return rfb;
        });
    }

    private void handleInput(String sessionId, byte[] payload, Map<String, String> meta) {
        RfbSession rfb = sessions.get(sessionId);
        if (rfb == null || rfb.rfb == null) return;
        if (payload == null || payload.length == 0) return;
        try {
            JsonNode node = MAPPER.readTree(new String(payload));
            String type = node.path("type").asText("");
            switch (type) {
                case "mouse": {
                    int x = node.path("x").asInt();
                    int y = node.path("y").asInt();
                    int btn = node.path("button").asInt();
                    boolean pressed = node.path("pressed").asBoolean(false);
                    int mask = pressed ? buttonMask(btn) : 0;
                    rfb.rfb.sendPointerEvent(mask, x, y);
                    break;
                }
                case "wheel": {
                    int amount = node.path("amount").asInt();
                    rfb.rfb.sendPointerEvent(amount > 0 ? 8 : 16, 0, 0);
                    break;
                }
                case "key": {
                    int keyCode = node.path("keyCode").asInt();
                    boolean pressed = node.path("pressed").asBoolean(false);
                    rfb.rfb.sendKeyEvent(vncKeySym(keyCode), pressed);
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("VNC 输入处理异常: {}", e.getMessage());
        }
    }

    private int buttonMask(int btn) {
        switch (btn) {
            case 0: return 1;
            case 1: return 4;
            case 2: return 2;
            default: return 0;
        }
    }

    private int vncKeySym(int keyCode) {
        if (keyCode >= 65 && keyCode <= 90) return keyCode + 32;
        return keyCode;
    }

    private void stopSession(String sessionId) {
        RfbSession rfb = sessions.remove(sessionId);
        if (rfb != null) {
            rfb.stop();
            sendVncFrame(sessionId, "stopped", null);
            log.info("VNC 会话已停止: sessionId={}", sessionId);
        }
    }

    private void sendVncFrame(String sessionId, String action, byte[] payload) {
        Map<String, String> meta = Map.of(META_ACTION, action, META_SESSION_ID, sessionId);
        client.getTransport().send(FrameCodec.vncFrame(agentId, payload != null ? payload : new byte[0], meta));
    }

    public void stopAll() {
        sessions.forEach((id, rfb) -> rfb.stop());
        sessions.clear();
        executor.close();
    }

    private class RfbSession implements Runnable {
        private final String sessionId;
        private final AtomicBoolean running = new AtomicBoolean(true);
        RfbClient rfb;
        private long intervalMs;

        RfbSession(String sessionId) {
            this.sessionId = sessionId;
            this.intervalMs = 1000L / DEFAULT_FPS;
        }

        @Override
        public void run() {
            try {
                rfb = new RfbClient();
                rfb.connect(VNC_HOST, VNC_PORT, null);
                log.info("RFB 连接成功: {}x{}", rfb.getFbWidth(), rfb.getFbHeight());
            } catch (Exception e) {
                log.error("RFB 连接失败: {}", e.getMessage());
                sendVncFrame(sessionId, "error", e.getMessage().getBytes());
                return;
            }
            int pushed = 0;
            while (running.get()) {
                try {
                    byte[] raw = rfb.readFrame();
                    if (raw == null) {
                        Thread.sleep(intervalMs);
                        continue;
                    }
                    int w = rfb.getFbWidth(), h = rfb.getFbHeight();
                    byte[] jpeg = bgraToJpeg(raw, w, h);
                    if (jpeg != null && jpeg.length > 0) {
                        Map<String, String> meta = Map.of(META_ACTION, "frame", META_SESSION_ID, sessionId);
                        client.getTransport().send(FrameCodec.vncFrame(agentId, jpeg, meta));
                        if (pushed++ < 5) log.info("[VNC_FRAME] sessionId={} {}x{} {}B", sessionId, w, h, jpeg.length);
                    }
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("VNC 帧处理异常: sessionId={}", sessionId, e);
                }
            }
            try { rfb.close(); } catch (Exception ignored) {}
            log.info("RFB 采集停止: sessionId={}", sessionId);
        }

        void stop() { running.set(false); }
    }

    private static byte[] bgraToJpeg(byte[] bgra, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < rgb.length; i++) {
            int off = i * 4;
            rgb[i] = ((bgra[off + 2] & 0xFF) << 16) | ((bgra[off + 1] & 0xFF) << 8) | (bgra[off] & 0xFF);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try { ImageIO.write(img, "jpg", bos); return bos.toByteArray(); }
        catch (Exception e) { return null; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
