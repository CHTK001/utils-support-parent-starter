package com.chua.remote.support.agent.desktop;

import com.chua.common.support.media.codec.VideoEncoder;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 桌面会话默认实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultDesktopSession implements DesktopSession {

    /**
     * 会话ID
     */
    private final String sessionId;

    /**
     * 视频编码器
     */
    private final VideoEncoder encoder;

    /**
     * 帧回调
     */
    private final BiConsumer<String, EncodedScreen> frameCallback;

    /**
     * 文本回调
     */
    private final BiConsumer<String, String> textCallback;

    /**
     * 指标推送执行器
     */
    private ScheduledExecutorService metricsScheduler;

    /**
     * 会话是否运行中
     */
    private volatile boolean running;

    /**
     * 采集器名称
     */
    private final String captureName;

    /**
     * 目标宽度
     */
    private int targetWidth;

    /**
     * 目标高度
     */
    private int targetHeight;

    /**
     * 画质值
     */
    private int quality = 80;

    /**
     * 画质模式
     */
    private QualityMode qualityMode = QualityMode.BALANCED;

    /**
     * 帧计数器
     */
    private final AtomicInteger frameCount = new AtomicInteger(0);

    /**
     * FPS 计数器
     */
    private final AtomicInteger fpsCounter = new AtomicInteger(0);

    /**
     * 使用捕获尺寸、编码器及回调构造桌面会话。
     *
     * @param sessionId 会话ID
     * @param captureW 原始捕获宽度
     * @param captureH 原始捕获高度
     * @param fps 帧率
     * @param encoder 视频编码器
     * @param frameCallback 编码帧回调
     * @param textCallback 文本消息回调
     */
    public DefaultDesktopSession(String sessionId, int captureW, int captureH, int fps,
                                VideoEncoder encoder, String captureName,
                                BiConsumer<String, EncodedScreen> frameCallback,
                                BiConsumer<String, String> textCallback) {
        this.sessionId = sessionId;
        this.targetWidth = captureW;
        this.targetHeight = captureH;
        this.encoder = encoder;
        this.captureName = captureName;
        this.frameCallback = frameCallback;
        this.textCallback = textCallback;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        if (metricsScheduler == null) {
            metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "desktop-metrics-" + sessionId);
                t.setDaemon(true);
                return t;
            });
            metricsScheduler.scheduleAtFixedRate(this::pushMetrics, 1, 1, TimeUnit.SECONDS);
        }
        log.info("桌面会话启动: sessionId={} target={}x{} mode={} encoder={}",
                sessionId, targetWidth, targetHeight, qualityMode, encoder.getCodecName());
    }

    @Override
    public void stop() {
        running = false;
        if (metricsScheduler != null) {
            metricsScheduler.shutdownNow();
            metricsScheduler = null;
        }
        encoder.close();
        log.info("桌面会话已停止: sessionId={}", sessionId);
    }

    @Override
    public void feedFrame(BufferedImage fullScreen) {
        if (!running) {
            return;
        }
        try {
            BufferedImage image = fullScreen;
            if (fullScreen.getWidth() != targetWidth || fullScreen.getHeight() != targetHeight) {
                image = scale(fullScreen, targetWidth, targetHeight);
            }
            if (qualityMode == QualityMode.SPEED) {
                BufferedImage gray = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
                java.awt.Graphics2D g = gray.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
                image = gray;
            }
            int count = frameCount.getAndIncrement();
            boolean keyFrame = count % 150 == 0;
            if (keyFrame) {
                encoder.forceKeyFrame();
            }
            byte[] encoded = encoder.encode(image);
            if (encoded != null && encoded.length > 0 && frameCallback != null) {
                fpsCounter.incrementAndGet();
                frameCallback.accept(sessionId, new EncodedScreen(targetWidth, targetHeight, keyFrame, encoded));
            }
        } catch (Throwable e) {
            log.error("[Desktop] feedFrame FAILED: {}", e.getMessage(), e);
        }
    }

    @Override
    public void feedFrame(ByteBuffer bgrData, int width, int height) {
        if (!running) {
            return;
        }
        try {
            // 直接传给编码器，跳过 BufferedImage
            int count = frameCount.getAndIncrement();
            boolean keyFrame = count % 150 == 0;
            if (keyFrame) {
                encoder.forceKeyFrame();
            }
            byte[] encoded = encoder.encode(bgrData, width, height);
            if (encoded != null && encoded.length > 0 && frameCallback != null) {
                fpsCounter.incrementAndGet();
                frameCallback.accept(sessionId, new EncodedScreen(targetWidth, targetHeight, keyFrame, encoded));
            }
        } catch (Throwable e) {
            log.error("[Desktop] feedFrame(ByteBuffer) FAILED: {}", e.getMessage(), e);
        }
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    @Override
    public void setTargetSize(int width, int height) {
        this.targetWidth = Math.max(64, width);
        this.targetHeight = Math.max(64, height);
    }

    @Override
    public void setQualityMode(QualityMode mode) {
        this.qualityMode = mode;
        switch (mode) {
            case SPEED -> { quality = 30; }
            case BALANCED -> { quality = 80; }
            case QUALITY -> { quality = 90; }
            case ORIGINAL -> { quality = 100; }
        }
        log.info("[DefaultDesktopSession] quality mode={}, quality={}", mode, quality);
    }

    @Override
    public void setQuality(int quality) {
        this.quality = Math.max(10, Math.min(100, quality));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getTargetWidth() {
        return targetWidth;
    }

    @Override
    public int getTargetHeight() {
        return targetHeight;
    }

    @Override
    public VideoEncoder getEncoder() {
        return encoder;
    }

    private void pushMetrics() {
        if (textCallback == null) {
            return;
        }
        try {
            Runtime rt = Runtime.getRuntime();
            long memTotal = rt.totalMemory();
            long memUsed = memTotal - rt.freeMemory();
            int currentFps = fpsCounter.getAndSet(0);
            String codecName = encoder != null ? encoder.getCodecName() : "unknown";
            String json = String.format(
                    "{\"type\":\"desktop_metrics\",\"sessionId\":\"%s\",\"fps\":%d,\"memUsed\":%d,\"memTotal\":%d,\"capture\":\"%s\",\"decoder\":\"%s\"}",
                    sessionId, currentFps, memUsed, memTotal, captureName, codecName);
            textCallback.accept(sessionId, json);
        } catch (Exception e) {
            log.debug("[DefaultDesktopSession] pushMetrics error: {}", e.getMessage());
        }
    }
}
