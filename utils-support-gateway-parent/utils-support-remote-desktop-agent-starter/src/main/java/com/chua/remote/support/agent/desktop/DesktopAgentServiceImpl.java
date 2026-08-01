package com.chua.remote.support.agent.desktop;

import com.chua.common.support.media.codec.ScreenCature;
import com.chua.common.support.media.codec.VideoEncoder;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.remote.support.agent.BaseRemoteAgent;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 默认桌面代理服务实现。
 *
 * <p>使用 SPI 加载 ScreenCature 采集器和 VideoEncoder 编码器，实现桌面远程控制。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DesktopAgentServiceImpl implements DesktopAgentService {

    private final BaseRemoteAgent agent;
    private final ScreenCature capture;
    private final Map<String, DesktopSession> sessions = new ConcurrentHashMap<>();
    private ExecutorService captureExecutor;
    private volatile boolean capturing;

    /**
     * 构造器。
     *
     * @param agent 远程代理实例
     */
    public DesktopAgentServiceImpl(BaseRemoteAgent agent) {
        this.agent = agent;
        this.capture = createCapture();
    }

    /**
     * 通过 SPI 创建屏幕采集器。
     *
     * @return ScreenCature 实例
     */
    private ScreenCature createCapture() {
        try {
            // 优先使用 javacv，回退到 robot
            ScreenCature cap = ServiceProvider.of(ScreenCature.class).getNewExtension("javacv");
            if (cap == null) {
                cap = ServiceProvider.of(ScreenCature.class).getNewExtension("robot");
            }
            if (cap != null) {
                log.info("[DesktopAgent] 采集器: {} (SPI)", cap.getClass().getSimpleName());
            }
            return cap;
        } catch (Exception e) {
            log.warn("[DesktopAgent] 创建采集器失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void handleConnect(String sessionId, Map<String, Object> target, Map<String, Object> auth) {
        if (capture == null) {
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"no capture\"}");
            return;
        }

        int clientW = 1920, clientH = 1080;
        if (target != null) {
            if (target.get("width") instanceof Number) clientW = ((Number) target.get("width")).intValue();
            if (target.get("height") instanceof Number) clientH = ((Number) target.get("height")).intValue();
        }
        int encW = clientW + (clientW & 1);
        int encH = clientH + (clientH & 1);

        log.info("[DesktopAgent] 创建会话: sessionId={} {}x{} enc={}x{}", sessionId, clientW, clientH, encW, encH);

        VideoEncoder encoder = createEncoder(encW, encH, 30);
        if (encoder == null) {
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"no encoder\"}");
            return;
        }

        DefaultDesktopSession session = new DefaultDesktopSession(sessionId, encW, encH, 30, encoder,
                (sid, frame) -> agent.sendBinaryFrame((byte) 0xDF, sid, frame.width(), frame.height(), frame.keyFrame(), frame.data()),
                (sid, json) -> agent.sendToGateway(json));
        session.setTargetSize(clientW, clientH);
        sessions.put(sessionId, session);
        session.start();
        startCapture();

        agent.sendToGateway("{\"type\":\"connected\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"DESKTOP connected\"}");
        log.info("[DesktopAgent] 已连接: sessionId={}", sessionId);
    }

    @Override
    public void handleDisconnect(String sessionId) {
        DesktopSession session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
            log.info("[DesktopAgent] 已断开: sessionId={}", sessionId);
        }
        if (sessions.isEmpty()) {
            stopCapture();
        }
    }

    @Override
    public void handleDisconnectAll() {
        sessions.forEach((sid, session) -> session.stop());
        sessions.clear();
        stopCapture();
    }

    @Override
    public void handleControl(String sessionId, String type, Map<String, Object> payload) {
        DesktopSession s = sessions.get(sessionId);
        if (s == null) return;
        if ("desktop_control".equals(type)) {
            String action = (String) payload.get("action");
            if ("start".equals(action)) s.start();
            else if ("stop".equals(action)) s.stop();
        }
    }

    @Override
    public void updateTargetSize(String sessionId, int width, int height) {
        DesktopSession s = sessions.get(sessionId);
        if (s != null) s.setTargetSize(width, height);
    }

    @Override
    public void handleInput(String sessionId, String type, Map<String, Object> payload) {
        handleControl(sessionId, type, payload);
    }

    @Override
    public BaseRemoteAgent getAgent() {
        return agent;
    }

    /**
     * 通过 SPI 创建 VideoEncoder 实例。
     */
    private VideoEncoder createEncoder(int w, int h, int fps) {
        try {
            VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class)
                    .getNewExtension("javacv-ffmpeg", w, h, fps);
            if (encoder == null) {
                log.warn("[DesktopAgent] SPI 编码器创建失败");
                return null;
            }
            log.info("[DesktopAgent] 编码器: {} {}x{} {}fps", encoder.getCodecName(), w, h, fps);
            return encoder;
        } catch (Exception e) {
            log.error("[DesktopAgent] 编码器创建失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 启动采集循环。
     */
    private synchronized void startCapture() {
        if (capturing || capture == null) return;
        if (!capture.init(1920, 1080, 30)) {
            log.error("[DesktopAgent] 采集器初始化失败");
            return;
        }
        capturing = true;
        captureExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "desktop-capture"));
        captureExecutor.submit(this::captureLoop);
        log.info("[DesktopAgent] 采集已启动");
    }

    /**
     * 停止采集循环。
     */
    private synchronized void stopCapture() {
        capturing = false;
        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
            captureExecutor = null;
        }
        if (capture != null) capture.close();
    }

    /**
     * 采集循环：采集 → 分发到所有会话 → 编码 → 发送。
     */
    private void captureLoop() {
        while (capturing) {
            if (sessions.isEmpty()) {
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                continue;
            }
            try {
                ByteBuffer buf = capture.grabFrame();
                if (buf == null) {
                    Thread.sleep(1);
                    continue;
                }
                int w = capture.getWidth();
                int h = capture.getHeight();
                for (DesktopSession session : sessions.values()) {
                    if (session.isRunning()) {
                        buf.rewind();
                        session.feedFrame(buf, w, h);
                    }
                }
            } catch (Exception e) {
                log.warn("[DesktopAgent] 采集异常: {}", e.getMessage());
            }
            try { Thread.sleep(33); } catch (InterruptedException e) { break; }
        }
    }
}