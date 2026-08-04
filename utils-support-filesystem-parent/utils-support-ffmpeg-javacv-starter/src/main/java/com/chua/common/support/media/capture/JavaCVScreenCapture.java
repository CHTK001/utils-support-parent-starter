package com.chua.common.support.media.capture;

import com.chua.common.support.media.codec.ScreenCature;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_free;
import static org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays;
import static org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

@Slf4j
@Spi("javacv")
public class JavaCVScreenCapture implements ScreenCature {

    private FFmpegFrameGrabber grabber;
    private int width;
    private int height;
    private int fps;
    private volatile boolean initialized;

    private SwsContext swsCtx;
    private BytePointer dstBuf;
    private AVFrame dstAvFrame;
    private int ySize;
    private int uvSize;
    private int srcStride;
    private int srcPixelFmt;
    private boolean converterReady;

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

            this.width = width;
            this.height = height;
            this.fps = fps;
            this.ySize = this.width * this.height;
            this.uvSize = (this.width / 2) * (this.height / 2);
            this.converterReady = false;

            this.initialized = true;
            log.info("[JavaCVScreenCapture] 已启动: {}x{}@{}fps", this.width, this.height, this.fps);
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
                Frame src = grabber.grabImage();
                if (src != null && src.image != null && src.image.length > 0 && src.image[0] != null) {
                    if (!converterReady) {
                        initConverterFromFrame(src);
                    }
                    return convertToYuv420p(src);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            log.warn("[JavaCVScreenCapture] 多次尝试后 frame 为 null");
            return null;
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 采集失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据首帧实际缓冲区大小检测源格式（gdigrab 可能输出 BGR24 或 BGRA）。
     */
    private void initConverterFromFrame(Frame src) {
        ByteBuffer buf = (ByteBuffer) src.image[0];
        int cap = buf.capacity();
        int bpp = cap / (width * height);
        if (bpp == 4) {
            srcPixelFmt = avutil.AV_PIX_FMT_BGRA;
            srcStride = width * 4;
        } else {
            srcPixelFmt = avutil.AV_PIX_FMT_BGR24;
            srcStride = width * 3;
        }
        log.info("[JavaCVScreenCapture] 首帧检测: bpp={} pixFmt={} stride={}", bpp, srcPixelFmt, srcStride);

        swsCtx = sws_getContext(
                width, height, srcPixelFmt,
                width, height, avutil.AV_PIX_FMT_YUV420P,
                SWS_BILINEAR, null, null, (double[]) null);
        if (swsCtx == null || swsCtx.isNull()) {
            throw new IllegalStateException("sws_getContext 返回 null");
        }

        dstAvFrame = av_frame_alloc();
        int bufSize = av_image_get_buffer_size(avutil.AV_PIX_FMT_YUV420P, width, height, 1);
        dstBuf = new BytePointer(av_malloc(bufSize));
        av_image_fill_arrays(dstAvFrame.data(), dstAvFrame.linesize(),
                dstBuf, avutil.AV_PIX_FMT_YUV420P, width, height, 1);
        dstAvFrame.format(avutil.AV_PIX_FMT_YUV420P);
        dstAvFrame.width(width);
        dstAvFrame.height(height);
        converterReady = true;
    }

    private Frame convertToYuv420p(Frame src) {
        try {
            ByteBuffer srcBuf = (ByteBuffer) src.image[0];
            if (!srcBuf.isDirect()) {
                byte[] arr = new byte[srcBuf.remaining()];
                srcBuf.get(arr);
                ByteBuffer direct = ByteBuffer.allocateDirect(arr.length);
                direct.put(arr);
                direct.flip();
                srcBuf = direct;
            }
            srcBuf.position(0);
            BytePointer srcData = new BytePointer(srcBuf);

            sws_scale(swsCtx,
                    new PointerPointer(srcData),
                    new IntPointer(srcStride),
                    0, height,
                    dstAvFrame.data(),
                    dstAvFrame.linesize());

            ByteBuffer dstBuffer = dstBuf.asBuffer();
            Frame result = new Frame(width, height, Frame.DEPTH_UBYTE, 2);
            result.image[0] = dstBuffer.slice(0, ySize);
            result.image[1] = dstBuffer.slice(ySize, uvSize);
            result.image[2] = dstBuffer.slice(ySize + uvSize, uvSize);
            result.imageWidth = width;
            result.imageHeight = height;
            result.imageStride = width;
            result.keyFrame = src.keyFrame;
            result.timestamp = src.timestamp;
            return result;
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 转换失败: {}", e.getMessage());
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
            if (dstBuf != null && !dstBuf.isNull()) {
                if (dstAvFrame != null && !dstAvFrame.isNull()) {
                    av_frame_free(dstAvFrame);
                }
                av_free(dstBuf);
            }
            if (swsCtx != null && !swsCtx.isNull()) {
                sws_freeContext(swsCtx);
            }
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
        swsCtx = null;
        dstBuf = null;
        dstAvFrame = null;
        log.info("[JavaCVScreenCapture] 已关闭");
    }
}