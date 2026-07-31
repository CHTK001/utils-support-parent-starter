package com.chua.remote.support.agent.desktop;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.media.codec.ScreenCature;
import com.chua.common.support.media.codec.VideoEncoder;
import com.chua.remote.support.agent.BaseRemoteAgent;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rust 桌面代理服务 — 通过原生 Rust FFI 实现屏幕采集和 H.264/265/266 编解码。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RustDesktopAgentService implements DesktopAgentService {

    /**
     * 底层远程代理
     */
    private final BaseRemoteAgent agent;

    /**
     * 屏幕采集器提供者
     */
    private final ScreenCature captureProvider;

    /**
     * 桌面会话映射表
     */
    private final Map<String, DesktopSession> desktopSessions = new ConcurrentHashMap<>();

    /**
     * 毫秒转纳秒常量
     */
    private static final long MS_TO_NS = 1_000_000L;

    /**
     * 默认帧率
     */
    private static final int DEFAULT_FPS = 60;

    /**
     * 默认采集宽度
     */
    private static final int DEFAULT_WIDTH = 1920;

    /**
     * 默认采集高度
     */
    private static final int DEFAULT_HEIGHT = 1080;

    /**
     * 无响应帧数阈值（超过此值重启采集器）
     */
    private static final int NULL_FRAME_RESTART_THRESHOLD = 180;

    /**
     * 无响应日志间隔帧数
     */
    private static final int NULL_FRAME_LOG_INTERVAL = 60;

    /**
     * 统计日志间隔帧数
     */
    private static final int STATS_LOG_INTERVAL = 15;

    /**
     * 最小采集间隔（毫秒）
     */
    private static final long MIN_FRAME_INTERVAL_MS = 1;

    /**
     * SPI 编码器类型：rust-h264
     */
    private static final String ENCODER_TYPE_RUST_H264 = "rust-h264";

    /**
     * SPI 编码器类型：javacv-ffmpeg
     */
    private static final String ENCODER_TYPE_JAVACV_FFMPEG = "javacv-ffmpeg";

    /**
     * JPEG 编码器全限定类名
     */
    private static final String JPEG_ENCODER_CLASS = "com.chua.common.support.media.codec.JpegVideoEncoder";

    /**
     * 控制指令类型：桌面控制
     */
    private static final String CONTROL_TYPE_DESKTOP_CONTROL = "desktop_control";

    /**
     * 控制动作：开始
     */
    private static final String ACTION_START = "start";

    /**
     * 控制动作：停止
     */
    private static final String ACTION_STOP = "stop";

    /**
     * 控制动作：画质
     */
    private static final String ACTION_QUALITY = "quality";

    /**
     * 控制动作：画质模式
     */
    private static final String ACTION_QUALITY_MODE = "quality_mode";

    /**
     * 控制动作：调整尺寸
     */
    private static final String ACTION_RESIZE = "resize";

    /**
     * 默认画质值
     */
    private static final int DEFAULT_QUALITY = 80;

    /**
     * 采集执行器线程名前缀
     */
    private static final String CAPTURE_THREAD_NAME = "rustdesktop-capture";

    /**
     * 构造 Rust 桌面代理服务。
     *
     * @param agent 底层远程代理
     */
    public RustDesktopAgentService(BaseRemoteAgent agent) {
        this.agent = agent;
        this.captureProvider = createScreenCapture();
    }

    /**
     * 创建屏幕采集器实例。
     *
     * @return ScreenCature 实例，创建失败返回 null
     */
    private ScreenCature createScreenCapture() {
        try {
            ScreenCature cap = ServiceProvider.of(ScreenCature.class).getNewExtension("javacv");
            if (cap != null) {
                log.info("[RustDesktopAgent] 采集器: JavaCVScreenCapture via SPI ({})",
                        System.getProperty("os.name"));
                return cap;
            }
        } catch (Exception e) {
            log.warn("[RustDesktopAgent] SPI 采集创建失败: {}", e.getMessage());
        }
        log.warn("[RustDesktopAgent] 未找到 ScreenCature SPI 实现");
        return null;
    }

    /**
     * 根据所有会话更新采集尺寸。
     */
    private void updateCaptureDimensions() {
        int maxW = 0, maxH = 0;
        for (DesktopSession s : desktopSessions.values()) {
            int w = s.getTargetWidth();
            int h = s.getTargetHeight();
            if (w > maxW || (w == maxW && h > maxH)) {
                maxW = w;
                maxH = h;
            }
        }
        if (maxW <= 0 || maxH <= 0) {
            maxW = DEFAULT_WIDTH;
            maxH = DEFAULT_HEIGHT;
        }
        maxW += maxW & 1;
        maxH += maxH & 1;

        if (maxW != captureW || maxH != captureH) {
            if (capturing) {
                restartCapture(maxW, maxH);
            } else {
                captureW = maxW;
                captureH = maxH;
            }
        }
    }

    @Override
    public synchronized void startCapture() {
        if (capturing || captureProvider == null) {
            return;
        }
        updateCaptureDimensions();

        if (!captureProvider.init(captureW, captureH, captureFps)) {
            log.error("[RustDesktopAgent] 采集器初始化失败");
            return;
        }

        capturing = true;
        frameCounter.set(0);
        captureExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, CAPTURE_THREAD_NAME));
        captureExecutor.submit(this::captureLoop);
        log.info("[RustDesktopAgent] 全局采集已启动 {}x{} {}fps", captureW, captureH, captureFps);
    }

    @Override
    public synchronized void stopCapture() {
        capturing = false;
        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
            captureExecutor = null;
        }
        if (captureProvider != null) {
            captureProvider.close();
        }
    }

    /**
     * 重启采集器。
     *
     * @param newW 新宽度
     * @param newH 新高度
     */
    private void restartCapture(int newW, int newH) {
        log.info("[RustDesktopAgent] 重启采集器: {}x{} -> {}x{}", captureW, captureH, newW, newH);
        boolean wasCapturing = capturing;
        if (wasCapturing) {
            capturing = false;
            if (captureProvider != null) {
                captureProvider.close();
            }
            if (captureExecutor != null) {
                captureExecutor.shutdownNow();
                captureExecutor = null;
            }
        }
        captureW = newW;
        captureH = newH;
        if (wasCapturing) {
            startCapture();
        }
    }

    /**
     * 采集循环（在独立线程中运行）。
     */
    private void captureLoop() {
        final long frameIntervalMs = 1000 / Math.min(DEFAULT_FPS, Math.max(1, captureFps));
        int nullFrameCount = 0;
        int frameCount = 0;

        while (capturing) {
            if (desktopSessions.isEmpty()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
                nullFrameCount = 0;
                continue;
            }

            long start = System.nanoTime();
            try {
                BufferedImage image = captureProvider.grabFrame();
                if (image == null) {
                    nullFrameCount++;
                    if (nullFrameCount == 1 || nullFrameCount % NULL_FRAME_LOG_INTERVAL == 0) {
                        log.info("[RustDesktopAgent] grabFrame() 返回 null count={}", nullFrameCount);
                    }
                    if (nullFrameCount > NULL_FRAME_RESTART_THRESHOLD) {
                        log.warn("[RustDesktopAgent] 采集器无响应 {} 次，尝试重启...", nullFrameCount);
                        restartCapture(captureW, captureH);
                        nullFrameCount = 0;
                    }
                    Thread.sleep(MIN_FRAME_INTERVAL_MS);
                    continue;
                }
                nullFrameCount = 0;

                int count = frameCounter.incrementAndGet();
                frameCount++;

                for (DesktopSession session : desktopSessions.values()) {
                    if (session.isRunning()) {
                        session.feedFrame(image);
                    }
                }

                if (count % STATS_LOG_INTERVAL == 0 || count == 1) {
                    log.info("[RustDesktopAgent] 已采集 {} 帧 (total {})(last frame {}x{})",
                            frameCount, count, image.getWidth(), image.getHeight());
                }
            } catch (Exception e) {
                log.warn("[RustDesktopAgent] 采集/编码异常: {}", e.getMessage());
            }

            long elapsed = System.nanoTime() - start;
            long sleepMs = frameIntervalMs - elapsed / MS_TO_NS;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /**
     * 创建视频编码器。
     *
     * @param w 视频宽度
     * @param h 视频高度
     * @param fps 帧率
     * @return VideoEncoder 实例
     */
    private VideoEncoder createEncoder(int w, int h, int fps) {
        try {
            log.info("[RustDesktopAgent] SPI加载编码器: VideoEncoder req={}x{}@{}fps", w, h, fps);
            VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class)
                    .getNewExtension(ENCODER_TYPE_RUST_H264, w, h, fps);
            if (encoder != null) {
                log.info("[RustDesktopAgent] SPI编码器(Rust)已创建: impl={} name={} {}x{} {}fps",
                        encoder.getClass().getName(), encoder.getCodecName(), w, h, fps);
                return encoder;
            }
            encoder = ServiceProvider.of(VideoEncoder.class)
                    .getNewExtension(ENCODER_TYPE_JAVACV_FFMPEG, w, h, fps);
            if (encoder != null) {
                log.info("[RustDesktopAgent] SPI编码器(JavaCV)已创建: impl={} name={} {}x{} {}fps",
                        encoder.getClass().getName(), encoder.getCodecName(), w, h, fps);
                return encoder;
            }
        } catch (Exception e) {
            log.warn("[RustDesktopAgent] SPI编码器创建失败: {}", e.getMessage());
        }
        return createJpegEncoder(w, h, fps);
    }

    /**
     * 创建 JPEG 降级编码器。
     *
     * @param w 视频宽度
     * @param h 视频高度
     * @param fps 帧率
     * @return VideoEncoder 实例，创建失败返回 null
     */
    private VideoEncoder createJpegEncoder(int w, int h, int fps) {
        log.warn("[RustDesktopAgent] 降级: 使用 JPEG 编码器");
        try {
            return (VideoEncoder) Class.forName(JPEG_ENCODER_CLASS)
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance(w, h, fps);
        } catch (Exception e) {
            log.warn("[RustDesktopAgent] JPEG编码器构造失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void handleConnect(String sessionId, Map<String, Object> target, Map<String, Object> auth) {
        if (captureProvider == null) {
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"no capture provider\"}");
            return;
        }

        int clientW = DEFAULT_WIDTH;
        int clientH = DEFAULT_HEIGHT;
        if (target != null) {
            if (target.get("width") instanceof Number) {
                clientW = ((Number) target.get("width")).intValue();
            }
            if (target.get("height") instanceof Number) {
                clientH = ((Number) target.get("height")).intValue();
            }
        }

        int encW = clientW + (clientW & 1);
        int encH = clientH + (clientH & 1);

        log.info("[RustDesktopAgent] 创建 DesktopSession: sessionId={} client={}x{} enc={}x{}",
                sessionId, clientW, clientH, encW, encH);

        VideoEncoder encoder;
        try {
            encoder = createEncoder(encW, encH, DEFAULT_FPS);
            if (encoder == null) {
                agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"encoder not found\"}");
                return;
            }
        } catch (Throwable e) {
            log.error("[RustDesktopAgent] 编码器创建失败: {}", e.getMessage(), e);
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"encoder init failed\"}");
            return;
        }

        DefaultDesktopSession session;
        try {
            session = new DefaultDesktopSession(sessionId, encW, encH, DEFAULT_FPS, encoder,
                    (sid, frame) -> agent.sendBinaryFrame((byte) 0xDF, sid, frame.width(), frame.height(), frame.keyFrame(), frame.data()),
                    (sid, json) -> agent.sendToGateway(json));
            session.setTargetSize(clientW, clientH);
        } catch (Throwable e) {
            log.error("[RustDesktopAgent] DesktopSession 创建失败: {}", e.getMessage(), e);
            agent.sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"desktop init failed\"}");
            return;
        }

        desktopSessions.put(sessionId, session);
        session.start();
        startCapture();

        agent.sendToGateway("{\"type\":\"connected\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"RUST_DESKTOP connected\"}");
        log.info("[RustDesktopAgent] connected: sessionId={}", sessionId);
    }

    @Override
    public void handleDisconnect(String sessionId) {
        DesktopSession session = desktopSessions.remove(sessionId);
        if (session != null) {
            session.stop();
            log.info("[RustDesktopAgent] 会话已断开: sessionId={}", sessionId);
        }
        if (desktopSessions.isEmpty()) {
            stopCapture();
        }
    }

    @Override
    public void handleDisconnectAll() {
        if (desktopSessions.isEmpty()) {
            return;
        }
        log.info("[RustDesktopAgent] 断开所有桌面会话: count={}", desktopSessions.size());
        desktopSessions.forEach((sid, session) -> session.stop());
        desktopSessions.clear();
        stopCapture();
    }

    @Override
    public void handleControl(String sessionId, String type, Map<String, Object> payload) {
        try {
            if (CONTROL_TYPE_DESKTOP_CONTROL.equals(type)) {
                String action = (String) payload.get("action");
                if (action == null) {
                    return;
                }
                DesktopSession s = desktopSessions.get(sessionId);
                if (s == null) {
                    return;
                }
                switch (action) {
                    case ACTION_START -> s.start();
                    case ACTION_STOP -> s.stop();
                    case ACTION_QUALITY -> {
                        int q = payload.get("value") instanceof Number ? ((Number) payload.get("value")).intValue() : DEFAULT_QUALITY;
                        s.setQuality(q);
                    }
                    case ACTION_QUALITY_MODE -> {
                        String mode = (String) payload.get("value");
                        if (mode != null) {
                            try {
                                s.setQualityMode(DesktopSession.QualityMode.valueOf(mode.toUpperCase()));
                            } catch (IllegalArgumentException e) {
                                log.warn("[RustDesktopAgent] 未知画质模式: {}", mode);
                            }
                        }
                    }
                    case ACTION_RESIZE -> {
                        int w = payload.get("width") instanceof Number ? ((Number) payload.get("width")).intValue() : 0;
                        int h = payload.get("height") instanceof Number ? ((Number) payload.get("height")).intValue() : 0;
                        if (w > 0 && h > 0) {
                            updateTargetSize(sessionId, w, h);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[RustDesktopAgent] 控制指令处理失败: {}", e.getMessage());
        }
    }

    @Override
    public void updateTargetSize(String sessionId, int width, int height) {
        DesktopSession s = desktopSessions.get(sessionId);
        if (s != null) {
            s.setTargetSize(width, height);
            log.info("[RustDesktopAgent] 更新目标尺寸: sessionId={} {}x{}", sessionId, width, height);
        }
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
     * 采集器是否正在运行。
     */
    private volatile boolean capturing;

    /**
     * 当前采集宽度
     */
    private int captureW;

    /**
     * 当前采集高度
     */
    private int captureH;

    /**
     * 当前采集帧率
     */
    private int captureFps = DEFAULT_FPS;

    /**
     * 帧计数器
     */
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    /**
     * 采集线程执行器
     */
    private ExecutorService captureExecutor;
}
