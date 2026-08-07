package com.chua.common.support.media.capture;

import com.chua.common.support.media.codec.ScreenCature;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * 基于 JavaCV(FFmpeg) 的屏幕采集器。
 *
 * <p>使用 gdigrab 采集桌面，直接返回 grabber 帧（单平面 BGR/BGRA），由下游
 * {@link com.chua.common.support.media.codec.VideoEncoder#encode(Frame)}
 * 内部的 {@link org.bytedeco.javacv.FFmpegFrameRecorder#record(Frame)} 完成
 * BGR→YUV420P 转换（JavaCV 自带的 sws_scale 路径，稳定无崩溃）。</p>
 *
 * <p>gdigrab 设备不支持 {@code setPixelFormat}，会在返回像素格式上保持默认
 * （BGR24/BGRA）。由于 grabber 复用内部缓冲区，本采集器对单平面数据做一次
 * 深度复制，保证下游消费安全。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("javacv")
public class JavaCVScreenCapture implements ScreenCature {

    private FFmpegFrameGrabber grabber;
    private int width;
    private int height;
    private int fps;
    private volatile boolean initialized;
    private int pixelStride;

    /**
     * 复用的 BGR/BGRA 字节数组，避免每帧分配 2.7MB 堆内存造成 GC 压力。
     * 在 init() 中按 (width * channels * height) 分配，copyFrame 复用同一份。
     * 仅本线程访问，但加 volatile 保证可见性。
     */
    private volatile byte[] reusableBgrBuf;

    /**
     * 复用的 ByteBuffer 视图，持有对 {@link #reusableBgrBuf} 的引用。
     */
    private volatile java.nio.ByteBuffer reusableBgrByteBuffer;

    /**
     * 复用的 Frame 对象，避免每帧 new Frame() 带来的分配。
     */
    private volatile Frame reusableResultFrame;

    @Override
    public boolean init(int width, int height, int fps) {
        close();
        try {
            grabber = new FFmpegFrameGrabber("desktop");
            grabber.setFormat("gdigrab");
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
            grabber.setFrameRate(Math.min(60, Math.max(1, fps)));
            grabber.setOption("probesize", "1M");
            grabber.setOption("analyzeduration", "0");
            grabber.setOption("framerate", String.valueOf(Math.min(60, Math.max(1, fps))));
            grabber.start();

            this.width = grabber.getImageWidth();
            this.height = grabber.getImageHeight();
            this.fps = fps;
            this.pixelStride = this.width * 3;
            this.initialized = true;

            // 预分配可复用缓冲区，避免每帧分配 ~2.7MB 堆内存（720p BGR）
            // 之前每帧 new byte[rowBytes] + ByteBuffer.allocate(rowBytes) 导致 GC 拖慢抓屏
            int rowBytes = this.pixelStride * this.height;
            this.reusableBgrBuf = new byte[rowBytes];
            this.reusableBgrByteBuffer = java.nio.ByteBuffer.wrap(reusableBgrBuf);
            this.reusableResultFrame = new Frame(this.width, this.height, Frame.DEPTH_UBYTE, 3);
            this.reusableResultFrame.imageWidth = this.width;
            this.reusableResultFrame.imageHeight = this.height;
            this.reusableResultFrame.imageStride = this.pixelStride;

            log.info("[JavaCVScreenCapture] 已启动: {}x{}@{}fps (gdigrab 单平面, 编码端转换YUV420P, 复用BGR缓冲={}KB)",
                    this.width, this.height, this.fps, rowBytes / 1024);
            return true;
        } catch (Exception e) {
            log.error("[JavaCVScreenCapture] 初始化失败: {}", e.getMessage(), e);
            close();
            return false;
        }
    }

    @Override
    public Frame grabFrame() {
        if (!initialized || grabber == null) {
            return null;
        }
        try {
            int retries = 3;
            while (retries-- > 0) {
                Frame src = grabber.grab();
                if (src != null && src.image != null && src.image.length >= 1
                        && src.image[0] != null && src.imageWidth > 0 && src.imageHeight > 0) {
                    return copyFrame(src);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            log.warn("[JavaCVScreenCapture] 多次尝试后 frame 仍无效");
            return null;
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 采集失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 深度复制 grabber 帧到复用的缓冲区，避免每帧分配 ~2.7MB 堆内存。
     * <p>支持单平面 BGR/BGRA，由 FFmpegFrameRecorder 自动识别格式。</p>
     * <p>使用堆上 byte[]（通过 ByteBuffer 视图）避免 JDK25 的 {@code jlong_disjoint_arraycopy}
     * 在非 8 字节对齐的 allocateDirect 缓冲区上崩溃。</p>
     */
    private Frame copyFrame(Frame src) {
        try {
            int channels = src.imageChannels > 0 ? src.imageChannels : 3;
            int stride = src.imageStride > 0 ? src.imageStride : width * channels;
            int rowBytes = stride * src.imageHeight;

            // 复用 buffer：尺寸变化（理论上不应发生）时才重新分配
            byte[] dstBuf = reusableBgrBuf;
            java.nio.ByteBuffer dstView = reusableBgrByteBuffer;
            Frame result = reusableResultFrame;
            if (dstBuf == null || dstBuf.length < rowBytes) {
                dstBuf = new byte[rowBytes];
                dstView = java.nio.ByteBuffer.wrap(dstBuf);
                reusableBgrBuf = dstBuf;
                reusableBgrByteBuffer = dstView;
                result = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
                result.imageWidth = width;
                result.imageHeight = height;
                result.imageStride = stride;
                reusableResultFrame = result;
            }

            Object srcObj = src.image[0];
            int toCopy = Math.min(rowBytes, dstBuf.length);
            if (srcObj instanceof java.nio.ByteBuffer buf) {
                // 在堆 buffer 之间直接拷贝，无 native 调用
                int srcRemaining = buf.remaining();
                int copyLen = Math.min(toCopy, srcRemaining);
                buf.position(0);
                buf.get(dstBuf, 0, copyLen);
                if (copyLen < toCopy) {
                    // src 比 dst 短，剩余部分填 0（罕见）
                    java.util.Arrays.fill(dstBuf, copyLen, toCopy, (byte) 0);
                }
            } else if (srcObj instanceof byte[] arr) {
                int copyLen = Math.min(toCopy, arr.length);
                System.arraycopy(arr, 0, dstBuf, 0, copyLen);
                if (copyLen < toCopy) {
                    java.util.Arrays.fill(dstBuf, copyLen, toCopy, (byte) 0);
                }
            } else if (srcObj instanceof org.bytedeco.javacpp.Pointer ptr) {
                // 单次拷贝：BytePointer → 堆 byte[]
                org.bytedeco.javacpp.BytePointer bp = new org.bytedeco.javacpp.BytePointer(ptr);
                bp.get(dstBuf, 0, toCopy);
            } else {
                log.warn("[JavaCVScreenCapture] 不支持的 image 类型: {}", srcObj.getClass().getName());
                return null;
            }
            dstView.position(0);

            // 复用同一 Frame 对象，但需保证 recorder.record() 同步调用（因为 image[0] 引用复用 buffer）
            // JavaCV FFmpegFrameRecorder.record(Frame) 是同步阻塞的，所以单线程 grab → encode 安全。
            result.image[0] = dstView;
            result.imageWidth = width;
            result.imageHeight = height;
            result.imageStride = stride;
            result.keyFrame = src.keyFrame;
            result.timestamp = src.timestamp;
            return result;
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 复制帧失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void close() {
        initialized = false;
        try {
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
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] close 异常: {}", e.getMessage());
        }
        grabber = null;
        // 释放复用缓冲区
        reusableBgrBuf = null;
        reusableBgrByteBuffer = null;
        reusableResultFrame = null;
        log.info("[JavaCVScreenCapture] 已关闭");
    }
}
