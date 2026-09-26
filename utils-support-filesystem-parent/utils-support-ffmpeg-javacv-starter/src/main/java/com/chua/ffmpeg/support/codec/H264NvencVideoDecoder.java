package com.chua.ffmpeg.support.codec;

import com.chua.common.support.codec.video.PixelFormat;
import com.chua.common.support.codec.video.VideoCodecOptions;
import com.chua.common.support.codec.video.VideoDecoder;
import com.chua.common.support.codec.video.VideoFrame;
import com.chua.common.support.codec.video.VideoPacket;
import com.chua.common.support.exception.CodecException;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于 NVDEC 硬件解码器的 H.264 视频解码器。
 *
 * <p>走 ffmpeg 的 {@code h264_cuvid}，逐个送入 {@link VideoPacket}、增量取出
 * {@link VideoFrame}。会话时基固定为 {@code 1/fps}：送入包的 PTS/DTS 按该时基从微秒换算成
 * 计步，取回帧的时间戳再换算回微秒，因此与 {@link H264NvencVideoEncoder} 构成对称闭环。</p>
 *
 * <p>与编码器同理，不做软解兜底：环境没有 NVDEC 时 {@link #isAvailable()} 返回 {@code false}，
 * 调用方应改用其它 {@code VideoDecoder} 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"h264_cuvid", "nvdec"}, order = 100)
@SpiDescribe("ffmpeg h264_cuvid 硬件视频解码器")
public class H264NvencVideoDecoder implements VideoDecoder {

    /**
     * ffmpeg 解码器名。
     */
    private static final String CODEC_NAME = "h264_cuvid";

    /**
     * 未指定帧率时的默认帧率。
     */
    private static final int DEFAULT_FPS = 30;

    /**
     * 单次交付循环的上限。
     */
    private static final int MAX_DRAIN_PER_CALL = 64;

    /**
     * 原生解码上下文。
     */
    private AVCodecContext context;

    /**
     * 复用的原生帧。
     */
    private AVFrame frame;

    /**
     * 复用的原生包。
     */
    private AVPacket packet;

    /**
     * 会话时基分母。
     */
    private int fps;

    /**
     * 行缓冲。
     */
    private byte[] row;

    @Override
    public boolean isAvailable() {
        return NvCodecProbe.probe(CODEC_NAME, false) == null;
    }

    @Override
    public boolean supports(String format) {
        if (!isAvailable() || format == null) {
            return false;
        }
        String name = format.toLowerCase(Locale.ROOT).replace('-', '_');
        return name.startsWith("h264") || name.startsWith("avc") || name.equals("nvdec");
    }

    @Override
    public void configure(VideoCodecOptions options) {
        releaseNative();
        String reason = NvCodecProbe.probe(CODEC_NAME, false);
        if (reason != null) {
            throw new CodecException("h264_cuvid unavailable: " + reason);
        }
        VideoCodecOptions actual = options == null ? new VideoCodecOptions() : options;
        fps = actual.getFps() > 0 ? Math.round(actual.getFps()) : DEFAULT_FPS;
        AVCodec codec = avcodec.avcodec_find_decoder_by_name(CODEC_NAME);
        context = avcodec.avcodec_alloc_context3(codec);
        if (context == null || context.isNull()) {
            throw new CodecException("avcodec_alloc_context3(%s) failed", CODEC_NAME);
        }
        context.time_base(new AVRational().num(1).den(fps));
        context.pkt_timebase(new AVRational().num(1).den(fps));
        AVDictionary dict = new AVDictionary(null);
        String gpu = actual.getExtra().get("gpu");
        if (gpu != null) {
            avutil.av_dict_set(dict, "gpu", gpu, 0);
        }
        int ret = avcodec.avcodec_open2(context, codec, dict);
        avutil.av_dict_free(dict);
        if (ret != 0) {
            releaseNative();
            throw new CodecException("avcodec_open2(%s) failed: %s", CODEC_NAME,
                    NvCodecProbe.strerror(ret));
        }
        frame = avutil.av_frame_alloc();
        packet = avcodec.av_packet_alloc();
    }

    @Override
    public List<VideoFrame> decode(VideoPacket input) {
        requireSession();
        byte[] data = input.data();
        avcodec.av_packet_unref(packet);
        if (avcodec.av_new_packet(packet, data.length) != 0) {
            throw new CodecException("av_new_packet(%d) failed", data.length);
        }
        packet.data().position(0);
        packet.data().put(data, 0, data.length);
        long pts = input.pts() < 0 ? avutil.AV_NOPTS_VALUE : toTicks(input.pts());
        long dts = input.dts() < 0 ? pts : toTicks(input.dts());
        packet.pts(pts).dts(dts);
        packet.time_base(new AVRational().num(1).den(fps));
        int ret = avcodec.avcodec_send_packet(context, packet);
        if (ret != 0) {
            throw new CodecException("avcodec_send_packet failed: %s", NvCodecProbe.strerror(ret));
        }
        return receiveFrames();
    }

    @Override
    public List<VideoFrame> drain() {
        requireSession();
        int ret = avcodec.avcodec_send_packet(context, (AVPacket) null);
        if (ret != 0 && ret != avutil.AVERROR_EOF) {
            throw new CodecException("avcodec_send_packet(NULL) failed: %s", NvCodecProbe.strerror(ret));
        }
        return receiveFrames();
    }

    @Override
    public void close() {
        releaseNative();
    }

    /**
     * 取出原生侧当前可交付的视频帧。
     *
     * @return 视频帧列表，可能为空
     */
    private List<VideoFrame> receiveFrames() {
        List<VideoFrame> frames = new ArrayList<>();
        for (int i = 0; i < MAX_DRAIN_PER_CALL; i++) {
            avutil.av_frame_unref(frame);
            int ret = avcodec.avcodec_receive_frame(context, frame);
            if (ret != 0) {
                return frames;
            }
            frames.add(toVideoFrame(frame));
        }
        return frames;
    }

    /**
     * 原生帧转为 SPI 帧，像素按平面紧致拷贝。
     *
     * @param source 原生帧
     * @return SPI 帧
     */
    private VideoFrame toVideoFrame(AVFrame source) {
        int width = source.width();
        int height = source.height();
        long pts = source.best_effort_timestamp() == avutil.AV_NOPTS_VALUE ? source.pts()
                : source.best_effort_timestamp();
        long micros = Math.round(pts * (double) avutil.AV_TIME_BASE / fps);
        if (source.format() == avutil.AV_PIX_FMT_NV12) {
            return VideoFrame.nv12(copyPlane(source.data(0), source.linesize(0), width, height),
                    copyPlane(source.data(1), source.linesize(1), width, (height + 1) / 2),
                    width, width, width, height, micros);
        }
        if (source.format() == avutil.AV_PIX_FMT_YUV420P) {
            int chromaWidth = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            return VideoFrame.yuv420p(
                    copyPlane(source.data(0), source.linesize(0), width, height),
                    copyPlane(source.data(1), source.linesize(1), chromaWidth, chromaHeight),
                    copyPlane(source.data(2), source.linesize(2), chromaWidth, chromaHeight),
                    width, chromaWidth, chromaWidth, width, height, micros);
        }
        throw new CodecException("h264_cuvid produced unsupported pixel format id %d",
                source.format());
    }

    /**
     * 按行剥离对齐填充，拷贝进紧致布局的直接内存缓冲。
     *
     * @param plane  原生平面指针
     * @param stride 原生行跨距
     * @param width  有效行宽
     * @param rows   行数
     * @return 直接内存缓冲，行跨距等于 {@code width}
     */
    private ByteBuffer copyPlane(BytePointer plane, int stride, int width, int rows) {
        if (row == null || row.length < width) {
            row = new byte[width];
        }
        ByteBuffer target = ByteBuffer.allocateDirect(width * rows);
        for (int y = 0; y < rows; y++) {
            plane.position((long) y * stride);
            plane.get(row, 0, width);
            target.put(row, 0, width);
        }
        target.flip();
        return target;
    }

    /**
     * 微秒换算为会话时基计步。
     *
     * @param micros 时间戳，单位微秒
     * @return 计步
     */
    private long toTicks(long micros) {
        return Math.round(micros * (double) fps / avutil.AV_TIME_BASE);
    }

    /**
     * 校验会话已打开。
     */
    private void requireSession() {
        if (context == null) {
            throw new CodecException("decoder session not configured, call configure() first");
        }
    }

    /**
     * 释放原生资源。
     */
    private void releaseNative() {
        if (packet != null) {
            avcodec.av_packet_unref(packet);
            avcodec.av_packet_free(packet);
            packet = null;
        }
        if (frame != null) {
            avutil.av_frame_unref(frame);
            avutil.av_frame_free(frame);
            frame = null;
        }
        if (context != null) {
            avcodec.avcodec_free_context(context);
            context = null;
        }
    }
}
