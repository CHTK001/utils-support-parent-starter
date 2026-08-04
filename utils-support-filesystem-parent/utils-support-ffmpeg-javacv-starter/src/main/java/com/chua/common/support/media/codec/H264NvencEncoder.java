package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
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
 * 基于 NVENC (h264_nvenc) 的硬件 H.264 编码器。
 *
 * <p>使用 FFmpegFrameRecorder 封装，通过 {@code setVideoCodecName("h264_nvenc")} 强制指定硬件加速。</p>
 * <p>仅接受 YUV420P 格式的 Frame，输入分辨率超过 1080p 时自动缩放到 1080p。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"h264", "nvenc", "javacv-ffmpeg"}, order = 10)
public class H264NvencEncoder implements VideoEncoder {

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
     * FFmpeg 帧录制器
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
     * 缩放输出帧缓冲区
     */
    private java.nio.ByteBuffer scaledBuf;

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
     * 编码器名称（尝试不同平台）
     */
    private String codecName;

    /**
     * 空构造。
     */
    public H264NvencEncoder() {
    }

    /**
     * 初始化 NVENC 编码器。
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

        // 按平台优先级尝试硬件编码器
        String[] candidates = {"h264_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox", "h264_vaapi"};
        for (String name : candidates) {
            if (tryInitCodec(name)) {
                this.codecName = name;
                this.started = true;
                log.info("[H264NvencEncoder] 已启动: {} {}x{} {}fps", name, encWidth, encHeight, fps);
                return;
            }
        }
        log.warn("[H264NvencEncoder] 所有硬件编码器均不可用");
    }

    /**
     * 尝试初始化指定编码器。
     *
     * @param codecName 编码器名称
     * @return 初始化成功返回 true
     */
    private boolean tryInitCodec(String codecName) {
        try {
            FFmpegFrameRecorder r = new FFmpegFrameRecorder(
                    new MemoryOutputStream(memoryStream), encWidth, encHeight);
            r.setVideoCodecName(codecName);
            r.setFrameRate(fps);
            r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            r.setInterleaved(true);
            r.setGopSize(GOP_SIZE);
            r.setOption("preset", "p1");
            r.setOption("tune", "ll");
            r.setOption("zerolatency", "1");
            r.start();
            this.recorder = r;
            log.info("[H264NvencEncoder] {} 初始化成功", codecName);
            return true;
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] {} 初始化失败: {}", codecName, e.getMessage());
            if (recorder != null) {
                try { recorder.stop(); } catch (Throwable ignored) {}
                try { recorder.release(); } catch (Throwable ignored) {}
                recorder = null;
            }
            return false;
        }
    }

    @Override
    public String getCodecName() {
        return codecName != null ? codecName : "none";
    }

    @Override
    public int getCodecId() {
        return org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
    }

    @Override
    public boolean isHardwareAccelerated() {
        return true;
    }

    @Override
    public synchronized void forceKeyFrame() {
        this.keyFrameRequested = true;
    }

    @Override
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
            log.warn("[H264NvencEncoder] 编码失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 编码单帧 YUV420P 数据。
     *
     * @param frame 输入 YUV Frame
     * @return 编码后的 H264 数据
     * @throws Exception 编码异常
     */
    private byte[] encodeFrame(Frame frame) throws Exception {
        int inW = frame.imageWidth;
        int inH = frame.imageHeight;

        if (keyFrameRequested || frameIndex == 0) {
            frame.keyFrame = true;
            keyFrameRequested = false;
        }
        frame.timestamp = pts++;

        long captureSize = memoryStream.size();

        if (inW == encWidth && inH == encHeight) {
            recorder.record(frame);
        } else {
            // 缩放路径：通过 sws_scale 缩放到目标尺寸
            Frame scaled = scaleFrame(frame, inW, inH);
            scaled.keyFrame = frame.keyFrame;
            scaled.timestamp = frame.timestamp;
            recorder.record(scaled);
        }

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

    /**
     * 缩放 YUV420P 帧到目标尺寸。
     *
     * @param frame 输入帧
     * @param inW   输入宽度
     * @param inH   输入高度
     * @return 缩放后的 Frame
     */
    private Frame scaleFrame(Frame frame, int inW, int inH) {
        BytePointer srcData;
        if (frame.image[0] instanceof java.nio.ByteBuffer buf) {
            srcData = new BytePointer(buf).position(0);
        } else {
            srcData = new BytePointer(new org.bytedeco.javacpp.Pointer(frame.image[0]).position(0));
        }

        // 分配或复用缩放缓冲区
        int ySize = encWidth * encHeight;
        int uSize = (encWidth / 2) * (encHeight / 2);
        int totalSize = ySize + uSize * 2;
        if (scaledBuf == null || scaledBuf.capacity() < totalSize) {
            scaledBuf = java.nio.ByteBuffer.allocateDirect(totalSize);
        }
        scaledBuf.clear();

        // sws_scale 输出到临时 AVFrame
        org.bytedeco.ffmpeg.avutil.AVFrame tmpFrame = org.bytedeco.ffmpeg.global.avutil.av_frame_alloc();
        int size = org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size(
                avutil.AV_PIX_FMT_YUV420P, encWidth, encHeight, 1);
        BytePointer tmpBuf = new BytePointer(org.bytedeco.ffmpeg.global.avutil.av_malloc(size));
        org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays(
                new PointerPointer(tmpFrame), tmpFrame.linesize(), tmpBuf,
                avutil.AV_PIX_FMT_YUV420P, encWidth, encHeight, 1);
        tmpFrame.format(avutil.AV_PIX_FMT_YUV420P);
        tmpFrame.width(encWidth);
        tmpFrame.height(encHeight);

        SwsContext sws = sws_getCachedContext(swsCtx, inW, inH, avutil.AV_PIX_FMT_YUV420P,
                encWidth, encHeight, avutil.AV_PIX_FMT_YUV420P, SWS_BILINEAR, null, null, (double[]) null);
        this.swsCtx = sws;

        int[] srcStride = new int[]{inW, inW / 2, inW / 2};
        sws_scale(sws, new PointerPointer(srcData), new IntPointer(srcStride),
                0, inH, new PointerPointer(tmpFrame), tmpFrame.linesize());

        // 复制到 JavaCV Frame
        byte[] plane = new byte[Math.max(ySize, uSize)];
        new BytePointer(tmpFrame.data(0)).get(plane, 0, ySize);
        scaledBuf.put(plane, 0, ySize);
        new BytePointer(tmpFrame.data(1)).get(plane, 0, uSize);
        scaledBuf.put(plane, 0, uSize);
        new BytePointer(tmpFrame.data(2)).get(plane, 0, uSize);
        scaledBuf.put(plane, 0, uSize);
        scaledBuf.rewind();

        org.bytedeco.ffmpeg.global.avutil.av_frame_free(tmpFrame);
        org.bytedeco.ffmpeg.global.avutil.av_free(tmpBuf);

        Frame result = new Frame(encWidth, encHeight, Frame.DEPTH_UBYTE, 2);
        result.imageWidth = encWidth;
        result.imageHeight = encHeight;
        result.imageStride = encWidth;
        result.image[0] = scaledBuf;
        return result;
    }

    @Override
    public synchronized void setCrf(int crf) {
        // NVENC 通过 bit_rate 控制质量，recorder 不直接支持动态修改
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        if (recorder != null) {
            try { recorder.flush(); } catch (Throwable ignored) {}
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
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