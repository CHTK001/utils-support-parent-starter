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

            log.info("[JavaCVScreenCapture] 已启动: {}x{}@{}fps (gdigrab 单平面, 编码端转换YUV420P)",
                    this.width, this.height, this.fps);
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
     * 深度复制 grabber 帧到独立 ByteBuffer，避免 grabber 复用内部 buffer。
     *
     * <p>支持单平面 BGR/BGRA，由 FFmpegFrameRecorder 自动识别格式。</p>
     * <p>使用堆上 ByteBuffer 避免 JDK25 的 {@code jlong_disjoint_arraycopy}
     * 在非 8 字节对齐的 allocateDirect 缓冲区上崩溃。</p>
     */
    private Frame copyFrame(Frame src) {
        try {
            int channels = src.imageChannels > 0 ? src.imageChannels : 3;
            int stride = src.imageStride > 0 ? src.imageStride : width * channels;
            int rowBytes = stride * src.imageHeight;

            Object srcObj = src.image[0];
            java.nio.ByteBuffer srcBuf;
            if (srcObj instanceof java.nio.ByteBuffer buf) {
                srcBuf = buf;
            } else if (srcObj instanceof byte[] arr) {
                srcBuf = java.nio.ByteBuffer.wrap(arr);
            } else if (srcObj instanceof org.bytedeco.javacpp.Pointer ptr) {
                srcBuf = new org.bytedeco.javacpp.BytePointer(ptr).asBuffer();
            } else {
                log.warn("[JavaCVScreenCapture] 不支持的 image 类型: {}", srcObj.getClass().getName());
                return null;
            }

            // 堆上 ByteBuffer 避免 jlong_disjoint_arraycopy 在非对齐 allocateDirect 上崩溃
            java.nio.ByteBuffer dst = java.nio.ByteBuffer.allocate(rowBytes);

            // 一次性 System.arraycopy（堆 byte[] 不会触发 jlong_disjoint_arraycopy 崩溃）
            // 把 srcBuf 拷到临时 heap byte[]，再一次性写入 dst
            byte[] tmpArr = new byte[rowBytes];
            srcBuf.position(0);
            srcBuf.get(tmpArr);
            dst.put(tmpArr);
            dst.position(0);

            Frame result = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
            result.image[0] = dst;
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
        log.info("[JavaCVScreenCapture] 已关闭");
    }
}
