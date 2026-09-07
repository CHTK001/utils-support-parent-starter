package com.chua.remote.agent;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.nativevideocodec.support.NativeVideoCodec;
import lombok.extern.slf4j.Slf4j;

/**
 * H.264 编码器 SPI 实现（原始 RGB 帧 → H264，不经 BufferedImage）。
 *
 * <p>经 {@link NativeVideoCodec}（Rust JNI 原生编码器）编码：原始 RGB 帧显式转换
 * 为 BGR24 后送入原生句柄，编码层经 SPI 架构加载（@Spi("h264")），不直连。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("h264")
public class H264FrameEncoder implements FrameEncoderSpi {

    /** 编码器原生句柄 */
    private long encoderHandle;

    /** 视频宽度 */
    private int width;

    /** 视频高度 */
    private int height;

    /** 帧率 */
    private int fps;

    @Override
    public String codecName() {
        return "h264";
    }

    @Override
    public synchronized byte[] encode(NativeFrame frame, int quality) {
        if (frame == null) {
            return new byte[0];
        }
        ensureInitialized(frame.width(), frame.height(), 30);
        if (encoderHandle == 0) {
            return new byte[0];
        }
        try {
            // RGB→BGR24 显式转换（原生 H264 编码器输入为 BGR24——显式拷贝，不经 BufferedImage）
            byte[] rgb = frame.pixels();
            byte[] bgr = new byte[rgb.length];
            for (int i = 0; i + 2 < rgb.length; i += 3) {
                bgr[i] = rgb[i + 2];
                bgr[i + 1] = rgb[i + 1];
                bgr[i + 2] = rgb[i];
            }
            return NativeVideoCodec.h264Encode(encoderHandle, bgr, width, height);
        } catch (Throwable e) {
            log.warn("[H264FrameEncoder] 编码失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 按尺寸初始化原生编码器（尺寸变化时重建）。
     */
    private synchronized void ensureInitialized(int w, int h, int f) {
        if (encoderHandle != 0 && w == width && h == height) {
            return;
        }
        close();
        this.width = w;
        this.height = h;
        this.fps = f;
        this.encoderHandle = NativeVideoCodec.h264EncoderCreate(w, h, f, 23, 1, 1);
        log.info("[H264FrameEncoder] 已启动: {}x{} {}fps handle={}", w, h, f, encoderHandle);
    }

    @Override
    public synchronized void close() {
        if (encoderHandle != 0) {
            NativeVideoCodec.h264EncoderFree(encoderHandle);
            encoderHandle = 0;
        }
    }
}
