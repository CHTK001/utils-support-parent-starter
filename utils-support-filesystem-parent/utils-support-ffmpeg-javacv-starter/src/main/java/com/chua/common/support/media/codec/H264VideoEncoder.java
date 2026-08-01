package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.ffmpeg.support.codec.EncodesFrame;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 JavaCV FFmpeg 的 H.264/AVC 编码器。
 *
 * <p>支持零拷贝 Frame 路径和 BufferedImage 降级路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"h264", "javacv-ffmpeg"}, order = 50)
public class H264VideoEncoder implements VideoEncoder, EncodesFrame {

    /**
     * 实例唯一标识（纳秒时间戳）
     */
    private final long instanceId = System.nanoTime();

    /**
     * FFmpeg 帧录制器
     */
    private FFmpegFrameRecorder recorder;

    /**
     * 内存输出流
     */
    private ByteArrayOutputStream memoryStream;

    /**
     * 视频宽度
     */
    private int width;

    /**
     * 视频高度
     */
    private int height;

    /**
     * 帧率
     */
    private int fps;

    /**
     * 是否请求关键帧
     */
    private boolean keyFrameRequested;

    /**
     * 时间戳计数器
     */
    private long pts;

    /**
     * 编码器是否已启动
     */
    private boolean started;

    /**
     * 已编码帧索引
     */
    private long frameIndex;

    /**
     * BufferedImage 转换器
     */
    private Java2DFrameConverter bufferedImageConverter;

    /**
     * H.264 编码格式名称
     */
    private static final String CODEC_NAME_H264 = "h264";

    /**
     * H.264 编码格式标识
     */
    private static final String FORMAT_H264 = "h264";

    /**
     * 内存输出流初始容量（字节）
     */
    private static final int MEMORY_STREAM_INITIAL_CAPACITY = 64 * 1024;

    /**
     * GOP 大小（关键帧间隔）
     */
    private static final int GOP_SIZE = 150;

    /**
     * H.264 编码 preset 选项的 key（"preset"）
     */
    private static final String KEY_PRESET = "preset";

    /**
     * H.264 编码 tune 选项的 key（"tune"）
     */
    private static final String KEY_TUNE = "tune";

    /**
     * H.264 编码 profile 选项的 key（"profile"）
     */
    private static final String KEY_PROFILE = "profile";

    /**
     * H.264 编码 preset 值（"ultrafast"）
     */
    private static final String VAL_PRESET = "ultrafast";

    /**
     * H.264 编码 tune 值（"zerolatency"）
     */
    private static final String VAL_TUNE = "zerolatency";

    /**
     * H.264 编码 profile 值（"main"）
     */
    private static final String VAL_PROFILE = "baseline";

    /**
     * 空构造。
     */
    public H264VideoEncoder() {
    }

    /**
     * 使用宽高和帧率构造并初始化。
     *
     * @param width 视频宽度
     * @param height 视频高度
     * @param fps 帧率
     */
    public H264VideoEncoder(int width, int height, int fps) {
        init(width, height, fps);
    }

    /**
     * 使用包装类型宽高和帧率构造，null 时跳过初始化。
     *
     * @param width 视频宽度
     * @param height 视频高度
     * @param fps 帧率
     */
    public H264VideoEncoder(Integer width, Integer height, Integer fps) {
        if (width != null && height != null && fps != null) {
            init(width, height, fps);
        }
    }

    /**
     * 使用可变参数构造，前三个参数分别为宽高和帧率。
     *
     * @param args 可变参数数组
     */
    public H264VideoEncoder(Object... args) {
        if (args != null && args.length >= 3
                && args[0] instanceof Number
                && args[1] instanceof Number
                && args[2] instanceof Number) {
            init(((Number) args[0]).intValue(),
                    ((Number) args[1]).intValue(),
                    ((Number) args[2]).intValue());
        }
    }

    public synchronized void init(int width, int height, int fps) {
        close();
        this.width = ensureEven(width);
        this.height = ensureEven(height);
        this.fps = Math.max(1, fps);
        this.memoryStream = new ByteArrayOutputStream(MEMORY_STREAM_INITIAL_CAPACITY);
        try {
            this.recorder = new FFmpegFrameRecorder(new MemoryOutputStream(memoryStream), this.width, this.height);
            this.recorder.setFormat(FORMAT_H264);
            this.recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            this.recorder.setFrameRate(this.fps);
            this.recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            this.recorder.setVideoQuality(0);
            this.recorder.setInterleaved(true);
            this.recorder.setGopSize(GOP_SIZE);
            this.recorder.start();
            this.started = true;
            this.bufferedImageConverter = new Java2DFrameConverter();
            log.info("[H264VideoEncoder] 已启动: {}x{} {}fps (H264 内存编码)", this.width, this.height, this.fps);
        } catch (Throwable e) {
            log.error("[H264VideoEncoder] 初始化失败: {} (cause={})",
                    e.getMessage(), e.getCause() == null ? "none" : e.getCause().getMessage(), e);
            this.recorder = null;
            this.started = false;
        }
    }

    /**
     * 确保数值为偶数（视频宽高通常需要偶数）。
     *
     * @param v 原始数值
     * @return 调整后的偶数
     */
    private static int ensureEven(int v) {
        return v + (v & 1);
    }

    @Override
    public String getCodecName() {
        return CODEC_NAME_H264;
    }

    @Override
    public int getCodecId() {
        return avcodec.AV_CODEC_ID_H264;
    }

    @Override
    public boolean isHardwareAccelerated() {
        return false;
    }

    @Override
    public synchronized void forceKeyFrame() {
        this.keyFrameRequested = true;
    }

    private void ensureInitialized(int w, int h, int f) {
        if (!started || recorder == null) {
            init(w, h, f);
        }
    }

    @Override
    public synchronized byte[] encode(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        ensureInitialized(image.getWidth(), image.getHeight(), 30);
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            BufferedImage bgr = ensureBgr(image);
            if (bufferedImageConverter == null) {
                bufferedImageConverter = new Java2DFrameConverter();
            }
            Frame frame = bufferedImageConverter.convert(bgr);
            if (frame == null) {
                return new byte[0];
            }
            return encodeInternal(frame);
        } catch (Throwable e) {
            log.warn("[H264VideoEncoder] encode(BufferedImage) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    public synchronized byte[] encode(Frame frame) {
        if (frame == null) {
            return new byte[0];
        }
        ensureInitialized(Math.max(frame.imageWidth, 640), Math.max(frame.imageHeight, 480), 30);
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            return encodeInternal(frame);
        } catch (Throwable e) {
            log.warn("[H264VideoEncoder] encode(Frame) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    public synchronized byte[] encode(ByteBuffer bgrData, int width, int height) {
        if (!started || bgrData == null || recorder == null) {
            ensureInitialized(width, height, 30);
        }
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            // 零拷贝：ByteBuffer → Frame（不复制数据）
            Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3, bgrData);
            return encodeInternal(frame);
        } catch (Throwable e) {
            log.warn("[H264VideoEncoder] encode(ByteBuffer) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 内部编码方法。
     *
     * @param frame 输入帧
     * @return 编码后的字节数组
     * @throws Exception 编码异常
     */
    private byte[] encodeInternal(Frame frame) throws Exception {
        long captureSize = memoryStream.size();
        if (keyFrameRequested) {
            frame.keyFrame = true;
            keyFrameRequested = false;
        }
        frame.timestamp = pts++;
        recorder.record(frame);
        byte[] all = memoryStream.toByteArray();
        byte[] frameBytes = new byte[all.length - (int) captureSize];
        if (frameBytes.length > 0) {
            System.arraycopy(all, (int) captureSize, frameBytes, 0, frameBytes.length);
        }
        frameIndex++;
        return frameBytes;
    }

    /**
     * 确保 BufferedImage 为 TYPE_3BYTE_BGR 格式。
     *
     * @param src 源图像
     * @return BGR 格式图像
     */
    private static BufferedImage ensureBgr(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return src;
        }
        BufferedImage bgr = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g = bgr.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return bgr;
    }

    @Override
    public synchronized void close() {
        started = false;
        if (recorder != null) {
            try {
                recorder.flush();
            } catch (Throwable ignored) {
            }
            try {
                recorder.stop();
            } catch (Throwable ignored) {
            }
            try {
                recorder.release();
            } catch (Throwable ignored) {
            }
            recorder = null;
        }
        if (bufferedImageConverter != null) {
            try {
                bufferedImageConverter.close();
            } catch (Throwable ignored) {
            }
            bufferedImageConverter = null;
        }
    }

    /**
     * 内存输出流适配器。
     */
    static final class MemoryOutputStream extends OutputStream {
        /**
         * 底层字节数组输出流
         */
        private final ByteArrayOutputStream backing;

        /**
         * 构造内存输出流。
         *
         * @param backing 底层字节数组输出流
         */
        MemoryOutputStream(ByteArrayOutputStream backing) {
            this.backing = backing;
        }

        @Override
        public void write(int b) {
            backing.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            backing.write(b, off, len);
        }

        @Override
        public void close() {
        }
    }
}
