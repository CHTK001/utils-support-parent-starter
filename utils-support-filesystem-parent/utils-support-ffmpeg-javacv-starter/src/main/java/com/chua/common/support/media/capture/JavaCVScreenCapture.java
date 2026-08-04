package com.chua.common.support.media.capture;

import com.chua.common.support.media.codec.ScreenCature;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/**
 * 基于 JavaCV(FFmpeg) 的屏幕采集器。
 *
 * <p>使用 gdigrab 采集桌面，设置 {@code AV_PIX_FMT_YUV420P} 后通过
 * {@code grabber.grab()} 直接得到 YUV420P 三平面 Frame（FFmpeg 内部 sws_scale 转换，
 * 不手动触碰 native 指针，避免崩溃）。</p>
 *
 * <p>因为 grabber 会复用内部缓冲区，这里对三平面做深度复制，保证下游安全消费。</p>
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
    private int ySize;
    private int uvSize;

    @Override
    public boolean init(int width, int height, int fps) {
        close();
        try {
            grabber = new FFmpegFrameGrabber("desktop");
            grabber.setFormat("gdigrab");
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
            grabber.setFrameRate(Math.min(60, Math.max(1, fps)));
            // 关键：让 FFmpeg 内部把 BGRA 转成 YUV420P
            grabber.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            grabber.setOption("probesize", "1M");
            grabber.setOption("analyzeduration", "0");
            grabber.setOption("framerate", String.valueOf(Math.min(60, Math.max(1, fps))));
            grabber.start();

            this.width = grabber.getImageWidth();
            this.height = grabber.getImageHeight();
            this.fps = fps;
            this.ySize = this.width * this.height;
            this.uvSize = (this.width / 2) * (this.height / 2);
            this.initialized = true;

            log.info("[JavaCVScreenCapture] 已启动: {}x{}@{}fps pixelFormat=YUV420P (gdigrab→grab→YUV420P)",
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
                if (src != null && src.image != null && src.image.length >= 3
                        && src.image[0] != null && src.image[1] != null && src.image[2] != null) {
                    return copyYuv420p(src);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            log.warn("[JavaCVScreenCapture] 多次尝试后 frame 仍非 YUV420P");
            return null;
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 采集失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 深度复制 YUV420P 三平面到独立缓冲区，避免 grabber 复用内部 buffer。
     */
    private Frame copyYuv420p(Frame src) {
        try {
            int totalSize = ySize + uvSize * 2;
            ByteBuffer dst = ByteBuffer.allocateDirect(totalSize);

            copyPlane(src.image[0], dst, 0, ySize);
            copyPlane(src.image[1], dst, ySize, uvSize);
            copyPlane(src.image[2], dst, ySize + uvSize, uvSize);

            dst.position(0);

            Frame result = new Frame(width, height, Frame.DEPTH_UBYTE, 2);
            result.image[0] = slice(dst, 0, ySize);
            result.image[1] = slice(dst, ySize, uvSize);
            result.image[2] = slice(dst, ySize + uvSize, uvSize);
            result.imageWidth = width;
            result.imageHeight = height;
            result.imageStride = width;
            result.keyFrame = src.keyFrame;
            result.timestamp = src.timestamp;
            return result;
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 复制帧失败: {}", e.getMessage());
            return null;
        }
    }

    private static void copyPlane(Object srcObj, ByteBuffer dst, int offset, int size) {
        ByteBuffer src;
        if (srcObj instanceof ByteBuffer buf) {
            src = buf;
        } else if (srcObj instanceof byte[] arr) {
            src = ByteBuffer.wrap(arr);
        } else if (srcObj instanceof org.bytedeco.javacpp.Pointer ptr) {
            src = new org.bytedeco.javacpp.BytePointer(ptr).asBuffer();
        } else {
            log.warn("[JavaCVScreenCapture] 不支持的 image 类型: {}", srcObj.getClass().getName());
            return;
        }
        src.position(0);
        byte[] tmp = new byte[Math.min(size, src.remaining())];
        src.get(tmp);
        dst.position(offset);
        dst.put(tmp);
    }

    private static ByteBuffer slice(ByteBuffer buf, int offset, int size) {
        ByteBuffer s = buf.duplicate();
        s.position(offset);
        s.limit(offset + size);
        return s.slice();
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