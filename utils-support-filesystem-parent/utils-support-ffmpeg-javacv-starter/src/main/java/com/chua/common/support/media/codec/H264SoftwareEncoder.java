package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;

/**
* 基于 libx264 的软件 H.264 编码器。
*
* <p>仅接受 YUV420P 格式的 Frame，输入分辨率超过 1080p 时自动缩放到 1080p。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"h264", "software", "javacv-ffmpeg"}, order = 99)
public class H264SoftwareEncoder implements VideoEncoder {

    /**
    * 最大编码宽度（1080p）
     */
    private static final int MAX_WIDTH = 1920;

    /**
    * 最大编码高度（1080p）
     */
    private static final int MAX_HEIGHT = 1080;

    /**
    * GOP 大小（关键帧间隔）
     */
    private static final int GOP_SIZE = 150;

    /**
    * 内存输出流初始容量
     */
    private static final int MEMORY_STREAM_INITIAL_CAPACITY = 64 * 1024;

    /**
    * ffmpeg 帧录制器
     */
    private FFmpegFrameRecorder recorder;

    /**
    * 内存输出流
     */
    private ByteArrayOutputStream memoryStream;

    /**
    * 色彩空间转换上下文（缩放用）
     */
    private SwsContext swsCtx;

    /**
    * 缩放后的 av帧
     */
    private org.bytedeco.ffmpeg.avutil.AVFrame swsFrame;

    /**
    * 缩放帧缓冲区
     */
    private BytePointer swsFrameBuf;

    /**
    * 编码宽度（≤1080p）
     */
    private int encWidth;

    /**
    * 编码高度（≤1080p）
     */
    private int encHeight;

    /**
    * 目标帧率
     */
    private int fps;

    /**
    * 帧时间戳
     */
    private long pts;

    /**
    * 是否请求了关键帧
     */
    private boolean keyFrameRequested;

    /**
    * 是否已启动
     */
    private boolean started;

    /**
    * 帧计数器
     */
    private long frameIndex;

    /**
    * CRF 值（18-35，越低质量越高）
     */
    private int crf = 23;

    /**
    * 空构造。
     */
    public H264SoftwareEncoder() {
    }

    /**
    * 初始化 libx264 编码器。
    *
    * @param width  输入宽度
    * @param height 输入高度
    * @param fps    目标帧率
     */
    private void init(int width, int height, int fps) {
        close();
        this.encWidth = Math.min(ensureEven(width), MAX_WIDTH);
        this.encHeight = Math.min(ensureEven(height), MAX_HEIGHT);
        this.fps = Math.max(1, fps);
        this.pts = 0;
        this.frameIndex = 0;
        this.keyFrameRequested = false;
        this.memoryStream = new ByteArrayOutputStream(MEMORY_STREAM_INITIAL_CAPACITY);

        try {
            FFmpegFrameRecorder r = new FFmpegFrameRecorder(
                    new MemoryOutputStream(memoryStream), encWidth, encHeight);
            r.setFormat("h264");
            r.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            r.setFrameRate(this.fps);
            r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            r.setOption("crf", String.valueOf(crf));
            r.setInterleaved(true);
            r.setGopSize(GOP_SIZE);
            r.setOption("preset", "ultrafast");
            r.setOption("tune", "zerolatency");
            r.setOption("profile", "baseline");
            r.start();
            this.recorder = r;
            this.started = true;
            log.info("[H264SoftwareEncoder] 已启动: {}x{} {}fps crf={}", encWidth, encHeight, fps, crf);
        } catch (Throwable e) {
            log.error("[H264SoftwareEncoder] 初始化失败: {} (cause={})",
                    e.getMessage(), e.getCause() == null ? "none" : e.getCause().getMessage(), e);
            this.recorder = null;
            this.started = false;
        }
    }

    @Override
    /** 获取codec名称 */
    public String getCodecName() {
        return "libx264";
    }

    @Override
    /** 获取codecid */
    public int getCodecId() {
        return avcodec.AV_CODEC_ID_H264;
    }

    @Override
    /** 是否hardware加速 */
    public boolean isHardwareAccelerated() {
        return false;
    }

    @Override
    /** force键帧 */
    public synchronized void forceKeyFrame() {
        this.keyFrameRequested = true;
    }

    @Override
    /** 编码 */
    public synchronized byte[] encode(Frame frame) {
        if (frame == null) {
            return new byte[0];
        }
        int inW = frame.imageWidth;
        int inH = frame.imageHeight;
        if (inW <= 0 || inH <= 0) {
            return new byte[0];
        }
        if (!started || recorder == null) {
            init(inW, inH, 30);
            if (!started) {
                return new byte[0];
            }
        }
        try {
            return encodeFrame(frame);
        } catch (Throwable e) {
            log.warn("[H264SoftwareEncoder] 编码失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
    * 编码单帧 YUV420P 数据。
    *
    * @param frame 输入 YUV 帧
    * @return 编码后的 H264 数据
    * @throws Exception 编码异常
     */
    private byte[] encodeFrame(Frame frame) throws Exception {
        int inW = frame.imageWidth;
        int inH = frame.imageHeight;

        if (inW == encWidth && inH == encHeight) {
            // 零拷贝路径：直接编码
            if (keyFrameRequested) {
                frame.keyFrame = true;
                keyFrameRequested = false;
            }
            frame.timestamp = pts++;
            long captureSize = memoryStream.size();
            recorder.record(frame);
            return extractFrameBytes(captureSize);
        }

        // 缩放路径：sws_scale 缩放到编码尺寸
        org.bytedeco.ffmpeg.avutil.AVFrame scaled = ensureSwsFrame();
        SwsContext sws = sws_getCachedContext(swsCtx, inW, inH, avutil.AV_PIX_FMT_YUV420P,
                encWidth, encHeight, avutil.AV_PIX_FMT_YUV420P, SWS_BILINEAR, null, null, (double[]) null);
        this.swsCtx = sws;

        BytePointer srcData;
        if (frame.image[0] instanceof java.nio.ByteBuffer buf) {
            srcData = new BytePointer(buf).position(0);
        } else {
            srcData = new BytePointer(new org.bytedeco.javacpp.Pointer(frame.image[0]).position(0));
        }

        int[] srcStride = new int[]{inW, inW / 2, inW / 2};
        var srcSlice = new PointerPointer(srcData);
        var dstSlice = new PointerPointer(scaled);
        sws_scale(sws, srcSlice, new IntPointer(srcStride), 0, inH, dstSlice, scaled.linesize());

 // 包装成 javacv 帧 供 recorder 使用
        Frame scaledFrame = new Frame(encWidth, encHeight, Frame.DEPTH_UBYTE, 2);
        scaledFrame.imageWidth = encWidth;
        scaledFrame.imageHeight = encHeight;
        scaledFrame.imageStride = encWidth;
        scaledFrame.image[0] = scaled.data(0).asBuffer();
        scaledFrame.image[1] = scaled.data(1).asBuffer();

        if (keyFrameRequested) {
            scaledFrame.keyFrame = true;
            keyFrameRequested = false;
        }
        scaledFrame.timestamp = pts++;
        long captureSize = memoryStream.size();
        recorder.record(scaledFrame);
        return extractFrameBytes(captureSize);
    }

    /**
    * 确保缩放帧缓冲区已分配。
    *
    * @return AVFrame 实例
     */
    private org.bytedeco.ffmpeg.avutil.AVFrame ensureSwsFrame() {
        if (swsFrame == null) {
            swsFrame = org.bytedeco.ffmpeg.global.avutil.av_frame_alloc();
            int size = org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size(
                    avutil.AV_PIX_FMT_YUV420P, encWidth, encHeight, 1);
            swsFrameBuf = new BytePointer(org.bytedeco.ffmpeg.global.avutil.av_malloc(size));
            org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays(
                    new PointerPointer(swsFrame), swsFrame.linesize(), swsFrameBuf,
                    avutil.AV_PIX_FMT_YUV420P, encWidth, encHeight, 1);
            swsFrame.format(avutil.AV_PIX_FMT_YUV420P);
            swsFrame.width(encWidth);
            swsFrame.height(encHeight);
        }
        return swsFrame;
    }

    /**
    * 从内存流中提取当前帧的编码数据。
    *
    * @param captureSize 提取前的流大小
    * @return 编码帧字节数组
     */
    private byte[] extractFrameBytes(long captureSize) {
        byte[] all = memoryStream.toByteArray();
        int len = all.length - (int) captureSize;
        if (len <= 0) {
            return new byte[0];
        }
        byte[] frameBytes = new byte[len];
        System.arraycopy(all, (int) captureSize, frameBytes, 0, len);
        frameIndex++;
        return frameBytes;
    }

    @Override
    /** 设置Crf */
    public synchronized void setCrf(int crf) {
        this.crf = Math.max(18, Math.min(35, crf));
        if (started) {
            int w = encWidth;
            int h = encHeight;
            int f = fps;
            close();
            init(w, h, f);
        }
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        started = false;
        if (recorder != null) {
            try { recorder.flush(); } catch (Throwable ignored) {}
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (swsFrame != null) {
            org.bytedeco.ffmpeg.global.avutil.av_frame_free(swsFrame);
            swsFrame = null;
        }
        if (swsFrameBuf != null) {
            org.bytedeco.ffmpeg.global.avutil.av_free(swsFrameBuf);
            swsFrameBuf = null;
        }
        if (swsCtx != null) {
            org.bytedeco.ffmpeg.global.swscale.sws_freeContext(swsCtx);
            swsCtx = null;
        }
    }

    /**
    * 确保数值为偶数。
    *
    * @param v 原始数值
    * @return 调整后的偶数
     */
    private static int ensureEven(int v) {
        return v + (v & 1);
    }

    /**
    * 内存输出流适配器。
     */
    private static final class MemoryOutputStream extends OutputStream {

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
        /** 写入 */
        public void write(int b) {
            backing.write(b);
        }

        @Override
        /** 写入 */
        public void write(byte[] b, int off, int len) {
            backing.write(b, off, len);
        }

        @Override
        /** 关闭 */
        public void close() {
        }
    }
}