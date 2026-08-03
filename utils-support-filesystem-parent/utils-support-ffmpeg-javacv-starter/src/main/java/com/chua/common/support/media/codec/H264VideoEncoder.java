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
 * 基于 JavaCV FFmpeg 的 H.264/AVC 编码器，支持软件（libx264）和硬件（NVENC）编码。
 *
 * <p>硬件编码使用 h264_nvenc + AV_PIX_FMT_NV12，软件编码使用 libx264 + AV_PIX_FMT_YUV420P。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"h264", "javacv-ffmpeg"}, order = 50)
public class H264VideoEncoder implements VideoEncoder, EncodesFrame {

    private final long instanceId = System.nanoTime();
    private FFmpegFrameRecorder recorder;
    private ByteArrayOutputStream memoryStream;
    private int width;
    private int height;
    private int fps;
    private boolean keyFrameRequested;
    private long pts;
    private boolean started;
    private long frameIndex;
    private Java2DFrameConverter bufferedImageConverter;
    private Frame cachedFrame;
    private int crf = 23;
    private boolean useHardware = false;
    private boolean nvencActive;

    private static final String CODEC_NAME_H264 = "h264";
    private static final String FORMAT_H264 = "h264";
    private static final int MEMORY_STREAM_INITIAL_CAPACITY = 64 * 1024;
    private static final int GOP_SIZE = 150;
    private static final String KEY_PRESET = "preset";
    private static final String KEY_TUNE = "tune";
    private static final String KEY_PROFILE = "profile";
    private static final String VAL_PRESET = "ultrafast";
    private static final String VAL_TUNE = "zerolatency";
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
        this.nvencActive = false;
        if (useHardware) {
            tryInitNvenc();
        }
        if (!started) {
            tryInitSoftware();
        }
        if (started) {
            this.bufferedImageConverter = new Java2DFrameConverter();
            log.info("[H264VideoEncoder] 已启动: {}x{} {}fps {}",
                    this.width, this.height, this.fps, nvencActive ? "NVENC硬件编码" : "H264软件编码");
        }
    }

    private void tryInitNvenc() {
        try {
            FFmpegFrameRecorder r = new FFmpegFrameRecorder(new MemoryOutputStream(memoryStream), this.width, this.height);
            r.setFormat(FORMAT_H264);
            r.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            r.setVideoCodecName("h264_nvenc");
            r.setFrameRate(this.fps);
            r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            r.setOption("preset", "p1");
            r.setOption("tune", "ll");
            r.setOption("forced_idr", "1");
            r.setOption("strict_gop", "1");
            r.setInterleaved(true);
            r.setGopSize(GOP_SIZE);
            r.start();
            this.recorder = r;
            this.started = true;
            this.nvencActive = true;
            log.info("[H264VideoEncoder] NVENC 初始化成功: {}x{} {}fps", this.width, this.height, this.fps);
        } catch (Throwable e) {
            log.warn("[H264VideoEncoder] NVENC 初始化失败，回退到软件编码: {} (cause={})",
                    e.getMessage(), e.getCause() == null ? "none" : e.getCause().getMessage());
        }
    }

    private void tryInitSoftware() {
        try {
            FFmpegFrameRecorder r = new FFmpegFrameRecorder(new MemoryOutputStream(memoryStream), this.width, this.height);
            r.setFormat(FORMAT_H264);
            r.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            r.setFrameRate(this.fps);
            r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            r.setOption("crf", String.valueOf(crf));
            r.setInterleaved(true);
            r.setGopSize(GOP_SIZE);
            r.setOption(KEY_PRESET, VAL_PRESET);
            r.setOption(KEY_TUNE, VAL_TUNE);
            r.setOption(KEY_PROFILE, VAL_PROFILE);
            r.start();
            this.recorder = r;
            this.started = true;
        } catch (Throwable e) {
            log.error("[H264VideoEncoder] 软件编码初始化失败: {} (cause={})",
                    e.getMessage(), e.getCause() == null ? "none" : e.getCause().getMessage(), e);
            this.recorder = null;
            this.started = false;
        }
    }

    /**
     * 动态调整编码质量（CRF），会重新创建编码器。
     *
     * @param crf CRF 值（18-35，越低质量越高）
     */
    public synchronized void setCrf(int crf) {
        this.crf = Math.max(18, Math.min(35, crf));
        if (started) {
            init(width, height, fps);
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
        return nvencActive ? "h264_nvenc" : CODEC_NAME_H264;
    }

    @Override
    public int getCodecId() {
        return avcodec.AV_CODEC_ID_H264;
    }

    @Override
    public boolean isHardwareAccelerated() {
        return nvencActive;
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
        ensureInitialized(width, height, 30);
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
            frame.imageWidth = width;
            frame.imageHeight = height;
            frame.imageStride = width * 3;
            java.nio.Buffer[] img = frame.image;
            if (img != null && img[0] instanceof ByteBuffer buf) {
                buf.clear();
                buf.put(bgrData);
                buf.rewind();
            }
            return encodeInternal(frame);
        } catch (Throwable e) {
            log.warn("[H264VideoEncoder] encode(ByteBuffer) 失败", e);
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
        if (log.isDebugEnabled()) {
            log.debug("encode frame: {}x{} depth={} channels={} imageLen={}",
                    frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels,
                    frame.image == null ? 0 : frame.image.length);
        }
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
