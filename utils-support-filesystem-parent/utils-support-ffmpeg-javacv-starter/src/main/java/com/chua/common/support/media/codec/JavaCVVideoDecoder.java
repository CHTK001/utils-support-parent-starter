package com.chua.common.support.media.codec;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 基于 javacv ffmpeg 的视频解码器，支持 H.264、H.265、H.266。
*
* <p>使用 FFmpegFrameGrabber 逐包解码，输出 ARGB 格式的 ByteBuffer。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class JavaCVVideoDecoder implements VideoDecoder {

    /**
    * 当前解码器使用的 codec 标识
     */
    private volatile int codecId;

    /**
    * 视频宽度
     */
    private volatile int width;

    /**
    * 视频高度
     */
    private volatile int height;

    /**
    * 解码器是否已初始化
     */
    private volatile boolean initialized;

    /**
    * 解码调用次数统计
     */
    private final AtomicInteger decodeCalls = new AtomicInteger(0);

    /**
    * 空返回次数统计
     */
    private final AtomicInteger emptyReturns = new AtomicInteger(0);

    /**
    * H.264 编码格式标识
     */
    private static final String FORMAT_H264 = "h264";

    /**
    * H.265 编码格式标识
     */
    private static final String FORMAT_H265 = "hevc";

    /**
    * H.266 编码格式标识
     */
    private static final String FORMAT_H266 = "h266";

    @Override
    /** 初始化 */
    public synchronized boolean init(int codecId, int width, int height) {
        this.codecId = codecId;
        this.width = width;
        this.height = height;
        this.initialized = true;
        log.info("[JavaCVVideoDecoder] 初始化 codecId={} {}x{}", codecId, width, height);
        return true;
    }

    @Override
    /** 解码 */
    public synchronized ByteBuffer decode(byte[] packet) {
        if (!initialized || packet == null || packet.length == 0) {
            return null;
        }
        FFmpegFrameGrabber grabber = null;
        try {
            decodeCalls.incrementAndGet();
            String format = getFormat(codecId);
            grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(packet));
            grabber.setFormat(format);
            grabber.start();
            Frame frame = grabber.grabImage();
            if (frame == null || frame.image == null) {
                emptyReturns.incrementAndGet();
                return null;
            }
            return frameToByteBuffer(frame);
        } catch (Throwable e) {
            log.warn("[JavaCVVideoDecoder] decode 失败: {}", e.getMessage());
            return null;
        } finally {
            if (grabber != null) {
                try {
                    grabber.stop();
                } catch (Exception ignored) {
                }
                try {
                    grabber.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
    * 根据 codecid 获取 ffmpeg 格式名称。
    *
    * @param codecId 编解码器标识
    * @return FFmpeg 格式名称
     */
    private static String getFormat(int codecId) {
        switch (codecId) {
            case 27:
                return FORMAT_H264;
            case 173:
                return FORMAT_H265;
            case 276:
                return FORMAT_H266;
            default:
                return FORMAT_H264;
        }
    }

    @Override
    /** 刷新 */
    public ByteBuffer[] flush() {
        return new ByteBuffer[0];
    }

    @Override
    /** 获取Width */
    public int getWidth() {
        return width;
    }

    @Override
    /** 获取Height */
    public int getHeight() {
        return height;
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        initialized = false;
        codecId = 0;
        width = 0;
        height = 0;
    }

    /**
    * 将 ffmpeg 帧转换为 byte缓冲（ARGB 格式）。
    *
    * @param frame ffmpeg 帧
    * @return ARGB 格式的 byte缓冲，转换失败返回 空
     */
    private static ByteBuffer frameToByteBuffer(Frame frame) {
        if (frame.image == null || frame.image.length == 0) {
            return null;
        }
        int w = frame.imageWidth;
        int h = frame.imageHeight;
        if (w <= 0 || h <= 0) {
            return null;
        }
        Object image = frame.image[0];
        if (image instanceof BufferedImage bi) {
            int[] argb = bi.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buffer = ByteBuffer.allocateDirect(argb.length * 4);
            buffer.asIntBuffer().put(argb);
            return buffer;
        }
        return null;
    }
}
