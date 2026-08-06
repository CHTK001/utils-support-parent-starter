package com.chua.remote.support.agent.desktop;

import com.chua.common.support.media.codec.VideoEncoder;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;

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
                                BiConsumer<String, EncodedScreen> frameCallback) {
        this.sessionId = sessionId;
        this.targetWidth = captureW;
        this.targetHeight = captureH;
        this.encoder = encoder;
        this.captureName = captureName;
        this.frameCallback = frameCallback;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        log.info("桌面会话启动: sessionId={} target={}x{} mode={} encoder={}",
                sessionId, targetWidth, targetHeight, qualityMode, encoder.getCodecName());
    }

    @Override
    public void stop() {
        running = false;
        encoder.close();
        log.info("桌面会话已停止: sessionId={}", sessionId);
    }

    @Override
    public void feedFrame(Frame frame) {
        if (!running || frame == null) {
            return;
        }
        try {
            int count = frameCount.getAndIncrement();
            boolean keyFrame = count == 0 || count % 150 == 0;
            if (keyFrame) {
                encoder.forceKeyFrame();
            }
            long t0 = System.nanoTime();
            byte[] encoded = encoder.encode(frame);
            long t1 = System.nanoTime();
            if (count < 16 || count % 30 == 0) {
                log.info("[Desktop] feedFrame-TIMING count={} encodeUs={} encodedLen={}",
                        count, (t1 - t0) / 1000, encoded != null ? encoded.length : 0);
            }
            if (encoded != null && encoded.length > 0 && frameCallback != null) {
                fpsCounter.incrementAndGet();
                frameCallback.accept(sessionId, new EncodedScreen(targetWidth, targetHeight, keyFrame, encoded));
            }
        } catch (Throwable e) {
            log.error("[Desktop] feedFrame FAILED: {}", e.getMessage(), e);
        }
    }

    @Override
    public void setTargetSize(int width, int height) {
        this.targetWidth = Math.max(64, width);
        this.targetHeight = Math.max(64, height);
    }

    @Override
    public QualityMode getQualityMode() {
        return qualityMode;
    }

    @Override
    public void setQualityMode(QualityMode mode) {
        this.qualityMode = mode;
        int crf;
        switch (mode) {
            case SPEED -> { quality = 30; crf = 35; }
            case BALANCED -> { quality = 80; crf = 28; }
            case QUALITY -> { quality = 90; crf = 20; }
            case ORIGINAL -> { quality = 100; crf = 17; }
            default -> { quality = 80; crf = 28; }
        }
        encoder.setCrf(crf);
        log.info("[DefaultDesktopSession] quality mode={}, quality={}, crf={}", mode, quality, crf);
    }

    @Override
    public void setQuality(int quality) {
        this.quality = Math.max(10, Math.min(100, quality));
        int crf = 35 - (this.quality - 10) * 20 / 90;
        encoder.setCrf(crf);
        log.info("[DefaultDesktopSession] quality={}, crf={}", this.quality, crf);
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

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public int getFps() {
        return fpsCounter.getAndSet(0);
    }

    }
