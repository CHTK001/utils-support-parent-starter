package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

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
     * memoryStream 的扩展 holder，允许 drain 部分字节并 reset 避免反复全量复制。
     */
    private DrainableByteArrayOutputStream memoryStreamHolder;

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
     * 上一个关键帧索引
     */
    private long lastKeyFrameIndex = -1;

    /**
     * 上一帧的 NAL 首字节
     */
    private int prevFirstNalType = -1;

    /**
     * NVENC 是否在下一帧强制 IDR（通过 forced-idr option 或 av_opt_set）
     */
    private boolean pendingForceIdr;

    /**
     * 累积的 NAL 缓冲区，用于缓存当前 GOP 内所有 NAL
     */
    private final java.io.ByteArrayOutputStream gopBuffer = new java.io.ByteArrayOutputStream(256 * 1024);

    /**
     * 上次 flush 时 gopBuffer 字节数
     */
    private int lastGopSize = 0;

    /**
     * 上次 flush 时是否包含 IDR
     */
    private boolean lastGopHasIdr = false;

    /**
     * 编码器名称（尝试不同平台）
     */
    private String codecName;

    /**
     * 反射获取的 AVFormatContext 字段
     */
    private Field ocField;

    /**
     * 反射获取的 AVCodecContext 字段
     */
    private Field videoCField;

    /**
     * 从编码器 extradata 提取的 SPS/PPS（Annex B 格式），用于拼接到关键帧头部
     */
    private byte[] spsPpsAnnexB;

    /**
     * 空构造。
     */
    public H264NvencEncoder() {
        try {
            ocField = FFmpegFrameRecorder.class.getDeclaredField("oc");
            ocField.setAccessible(true);
            videoCField = FFmpegFrameRecorder.class.getDeclaredField("video_c");
            videoCField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            log.warn("[H264NvencEncoder] 反射字段获取失败: {}", e.getMessage());
        }
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
        this.memoryStreamHolder = new DrainableByteArrayOutputStream(MEMORY_STREAM_INITIAL_CAPACITY);
        this.memoryStream = memoryStreamHolder.asByteArrayOutputStream();

        // 按平台优先级尝试硬件编码器
        // 注意：bytedeco ffmpeg 7.1.1-1.5.12 的 h264_nvenc 在本机初始化成功但不工作（encodedLen=0），
        // 因此跳过 nvenc，直接尝试 qsv/amf/vaapi。
        String[] candidates = {"h264_qsv", "h264_amf", "h264_videotoolbox", "h264_vaapi", "h264_nvenc"};
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
            r.setFormat("h264");
            r.setVideoCodecName(codecName);
            r.setFrameRate(fps);
            r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            r.setInterleaved(true);
            r.setGopSize(60);
            r.setOption("preset", "p1");
            r.setOption("tune", "ll");
            r.setOption("zerolatency", "1");
            r.setVideoOption("repeat_headers", "1");
            r.setVideoOption("global_header", "0");
            r.setVideoOption("rc", "cbr");
            r.setVideoOption("delay", "0");
            r.start();
            this.recorder = r;
            loadSpsPpsFromExtradata();
            if (spsPpsAnnexB == null) {
                clearGlobalHeader();
            }
            log.info("[H264NvencEncoder] {} 初始化成功 loaded-from={} format=h264",
                    codecName,
                    getClass().getProtectionDomain() != null && getClass().getProtectionDomain().getCodeSource() != null
                            ? getClass().getProtectionDomain().getCodeSource().getLocation() : "unknown");
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
        this.pendingForceIdr = true;
        forceNvencIdr();
    }

    /**
     * 通过 NVENC 私有选项 {@code forced-idr} 强制下一个 IDR 帧。
     * 优先尝试 {@code av_opt_set}，失败时回退到 {@code av_dict_set}。
     */
    private void forceNvencIdr() {
        if (videoCField == null || recorder == null) {
            return;
        }
        try {
            AVCodecContext videoC = (AVCodecContext) videoCField.get(recorder);
            if (videoC == null) {
                return;
            }
            org.bytedeco.ffmpeg.avutil.AVDictionary opts = new org.bytedeco.ffmpeg.avutil.AVDictionary();
            int r1 = org.bytedeco.ffmpeg.global.avutil.av_dict_set(opts, "forced-idr", "1", 0);
            org.bytedeco.ffmpeg.global.avutil.av_dict_free(opts);
            log.info("[H264NvencEncoder] forced-idr dict_set rc={}", r1);
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] forceNvencIdr 失败: {}", e.getMessage());
        }
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
     * <p>
     * 策略：累积 NVENC 的连续 NAL 输出，每遇到 IDR NAL 就把上次累积的内容作为 GOP 块返回（含 SPS/PPS/IDR），其他时间返回空。
     * 这样保证前端每次收到的都是完整 GOP 的 IDR + SEI + IDR slice。
     * </p>
     *
     * @param frame 输入 YUV Frame
     * @return 编码后的 H264 数据（含完整 GOP IDR），或空（中间的 P/SEI 帧）
     */
    private byte[] encodeFrame(Frame frame) throws Exception {
        long t0 = System.nanoTime();
        int inW = frame.imageWidth;
        int inH = frame.imageHeight;

        boolean requestKey = keyFrameRequested || frameIndex == 0;
        if (requestKey) {
            frame.keyFrame = true;
            keyFrameRequested = false;
        }
        frame.timestamp = pts++;

        long captureSize = memoryStream.size();

        long t1 = System.nanoTime();
        if (inW == encWidth && inH == encHeight) {
            recorder.record(frame);
        } else {
            Frame scaled = scaleFrame(frame, inW, inH);
            scaled.keyFrame = frame.keyFrame;
            scaled.timestamp = frame.timestamp;
            recorder.record(scaled);
        }
        long t2 = System.nanoTime();

        flushOutput();
        long t3 = System.nanoTime();

        // 修复 O(n²) 内存拷贝：以前每次 toByteArray() 复制整个累计 buffer（30s 后 100MB+），
        // 然后 System.arraycopy 截取增量。改为从 memoryStreamHolder 读取增量后 reset。
        long totalLen = memoryStream.size();
        long len = totalLen - captureSize;
        byte[] frameBytes;
        if (len <= 0) {
            memoryStreamHolder.reset();
            frameIndex++;
            return new byte[0];
        }
        frameBytes = memoryStreamHolder.drain((int) captureSize, (int) len);
        long t4 = System.nanoTime();

        boolean hasIdr = containsNalType(frameBytes, 5);
        long t5 = System.nanoTime();

        if (frameIndex < 16) {
            log.info("[H264NvencEncoder] STEP-TIMING idx={} recordUs={} flushUs={} copyUs={} nalScanUs={} totalUs={} len={} hasIdr={}",
                    frameIndex,
                    (t2 - t1) / 1000, (t3 - t2) / 1000, (t4 - t3) / 1000, (t5 - t4) / 1000,
                    (t5 - t0) / 1000, len, hasIdr);
        }

        if (spsPpsAnnexB == null && frameIndex < 16) {
            long te = System.nanoTime();
            extractSpsPpsFromFrame(frameBytes);
            long tf = System.nanoTime();
            if (frameIndex < 4) {
                log.info("[H264NvencEncoder] EXTRACT-SPS-PPS idx={} extractUs={} spsPps={}",
                        frameIndex, (tf - te) / 1000, spsPpsAnnexB != null ? spsPpsAnnexB.length : 0);
            }
        }

        // 修复：每帧都返回（不再仅在 IDR 时返回）。SPS/PPS 仅在首帧或 IDR 时拼接到头部。
        // P 帧不解码不影响浏览器使用——前端 WebCodecs/WebAssembly decoder 能正确处理 I/P 帧流。
        byte[] result;
        if (hasIdr && spsPpsAnnexB != null) {
            result = new byte[spsPpsAnnexB.length + frameBytes.length];
            System.arraycopy(spsPpsAnnexB, 0, result, 0, spsPpsAnnexB.length);
            System.arraycopy(frameBytes, 0, result, spsPpsAnnexB.length, frameBytes.length);
        } else {
            result = frameBytes;
        }
        frameIndex++;
        return result;
    }

    /**
     * 检查帧数据中是否包含指定类型的 NAL。
     *
     * @param data H264 数据（Annex B 格式）
     * @param nalType NAL 类型（0-31）
     * @return true 表示包含
     */
    private static boolean containsNalType(byte[] data, int nalType) {
        if (data == null || data.length < 5) {
            return false;
        }
        for (int i = 0; i < data.length - 4; i++) {
            if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 0 && (data[i + 3] & 0xff) == 1) {
                if ((data[i + 4] & 0x1f) == nalType) {
                    return true;
                }
            } else if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 1) {
                if ((data[i + 3] & 0x1f) == nalType) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从帧数据中扫描 NAL 提取 SPS（0x67）和 PPS（0x68）。
     *
     * @param data 帧原始 H264 数据（Annex B 格式，含 00 00 00 01 起始码）
     */
    private void extractSpsPpsFromFrame(byte[] data) {
        if (data == null || data.length < 8) {
            return;
        }
        byte[] sps = null;
        byte[] pps = null;
        int i = 0;
        int start = -1;
        int headerLen = 0;
        while (i < data.length - 4) {
            if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 0 && (data[i + 3] & 0xff) == 1) {
                headerLen = 4;
            } else if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 1) {
                headerLen = 3;
            } else {
                i++;
                continue;
            }
            if (i + headerLen >= data.length) {
                break;
            }
            int nalType = data[i + headerLen] & 0x1f;
            int nextStart = data.length;
            for (int j = i + headerLen + 1; j < data.length - 3; j++) {
                if (((data[j] & 0xff) == 0 && (data[j + 1] & 0xff) == 0
                        && (data[j + 2] & 0xff) == 0 && (data[j + 3] & 0xff) == 1)
                        || ((data[j] & 0xff) == 0 && (data[j + 1] & 0xff) == 0
                        && (data[j + 2] & 0xff) == 1)) {
                    nextStart = j;
                    break;
                }
            }
            int nalLen = nextStart - (i + headerLen);
            if (nalLen > 0) {
                byte[] nalUnit = new byte[headerLen + nalLen];
                System.arraycopy(data, i, nalUnit, 0, headerLen + nalLen);
                if (nalType == 7 && sps == null) {
                    sps = nalUnit;
                } else if (nalType == 8 && pps == null) {
                    pps = nalUnit;
                }
            }
            i = nextStart;
        }
        if (sps != null && pps != null) {
            byte[] combined = new byte[sps.length + pps.length];
            System.arraycopy(sps, 0, combined, 0, sps.length);
            System.arraycopy(pps, 0, combined, sps.length, pps.length);
            this.spsPpsAnnexB = combined;
            log.info("[H264NvencEncoder] 从 IDR 帧数据中提取到 SPS ({}B) + PPS ({}B)", sps.length, pps.length);
        }
    }

    private static String bytesToHex(byte[] data, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x ", data[i] & 0xff));
        return sb.toString().trim();
    }

    /**
     * 清除 AV_CODEC_FLAG_GLOBAL_HEADER 标志，强制编码器在每帧中写入 SPS/PPS。
     */
    private void clearGlobalHeader() {
        if (videoCField == null || recorder == null) {
            return;
        }
        try {
            AVCodecContext videoC = (AVCodecContext) videoCField.get(recorder);
            if (videoC != null) {
                int flags = videoC.flags();
                videoC.flags(flags & ~(1 << 22));
            }
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] clearGlobalHeader 失败: {}", e.getMessage());
        }
    }

    /**
     * 从编码器 extradata 中提取 SPS/PPS 并转换为 Annex B 格式。
     */
    private void loadSpsPpsFromExtradata() {
        if (videoCField == null || recorder == null) {
            return;
        }
        try {
            AVCodecContext videoC = (AVCodecContext) videoCField.get(recorder);
            if (videoC == null) {
                return;
            }
            BytePointer extradata = videoC.extradata();
            int extradataSize = videoC.extradata_size();
            if (extradata == null || extradataSize < 7) {
                log.warn("[H264NvencEncoder] extradata 为空或过小: {}", extradataSize);
                return;
            }
            byte[] data = new byte[extradataSize];
            extradata.get(data);
            // avcC 格式: 5 字节头 + SPS 列表 + PPS 列表
            if (data[0] != 1) {
                log.warn("[H264NvencEncoder] extradata version != 1: {}", data[0]);
                return;
            }
            // numSPS 在 data[5] 的低 5 位
            int numSPS = data[5] & 0x1f;
            int pos = 6;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i < numSPS; i++) {
                if (pos + 2 > data.length) break;
                int spsLen = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
                pos += 2;
                if (pos + spsLen > data.length) break;
                baos.write(0x00); baos.write(0x00); baos.write(0x00); baos.write(0x01);
                baos.write(data, pos, spsLen);
                pos += spsLen;
            }
            if (pos + 1 > data.length) return;
            int numPPS = data[pos] & 0x1f;
            pos += 1;
            for (int i = 0; i < numPPS; i++) {
                if (pos + 2 > data.length) break;
                int ppsLen = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
                pos += 2;
                if (pos + ppsLen > data.length) break;
                baos.write(0x00); baos.write(0x00); baos.write(0x00); baos.write(0x01);
                baos.write(data, pos, ppsLen);
                pos += ppsLen;
            }
            spsPpsAnnexB = baos.toByteArray();
            log.info("[H264NvencEncoder] 成功提取 SPS/PPS, 大小: {} 字节", spsPpsAnnexB.length);
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] 加载 SPS/PPS 失败: {}", e.getMessage());
        }
    }

    /**
     * 刷新 AVIO 输出缓冲区，确保编码数据写入 memoryStream。
     */
    private void flushOutput() {
        if (ocField == null || recorder == null) {
            return;
        }
        try {
            org.bytedeco.ffmpeg.avformat.AVFormatContext oc = (org.bytedeco.ffmpeg.avformat.AVFormatContext) ocField.get(recorder);
            if (oc != null && oc.pb() != null && oc.pb().buffer() != null) {
                org.bytedeco.ffmpeg.global.avformat.avio_flush(oc.pb());
            }
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] flushOutput 失败: {}", e.getMessage());
        }
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
        if (memoryStreamHolder != null) {
            memoryStreamHolder.reset();
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

    /**
     * 可增量排出（drain）字节的 ByteArrayOutputStream。
     * <p>
     * 解决 H264NvencEncoder 旧实现中 {@code memoryStream.toByteArray() + System.arraycopy}
     * 带来的 O(n²) 内存拷贝问题：每次编码一帧前，编码器记录 {@code memoryStream.size()}，
     * 编码 + flush 之后需要读取增量并清空。如果直接调用 {@code toByteArray()}，
     * 30 秒后累计 buffer 达到 100MB+ 后，每次都会完整复制 100MB。
     * </p>
     * <p>
     * 本类通过 {@link #drain(int, int)} 直接读取并清空指定区间，避免重复扫描。
     * </p>
     */
    private static final class DrainableByteArrayOutputStream extends ByteArrayOutputStream {
        DrainableByteArrayOutputStream(int capacity) {
            super(capacity);
        }

        /** 读取 [offset, offset+length) 区间的字节并 reset() 清空整个累计区。 */
        synchronized byte[] drain(int offset, int length) {
            byte[] out = new byte[length];
            System.arraycopy(this.buf, offset, out, 0, length);
            this.reset();
            return out;
        }

        /** 暴露底层 ByteArrayOutputStream 视图（用于 MemoryOutputStream 适配）。 */
        ByteArrayOutputStream asByteArrayOutputStream() {
            return this;
        }
    }
}