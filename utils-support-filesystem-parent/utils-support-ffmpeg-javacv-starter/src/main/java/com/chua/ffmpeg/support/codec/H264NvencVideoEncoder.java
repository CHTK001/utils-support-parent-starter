package com.chua.ffmpeg.support.codec;

import com.chua.common.support.codec.video.PixelFormat;
import com.chua.common.support.codec.video.VideoCodecOptions;
import com.chua.common.support.codec.video.VideoEncoder;
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
 * 基于 NVENC 硬件编码器的 H.264 视频编码器。
 *
 * <p>直接走 ffmpeg 的 {@code h264_nvenc}，逐帧送入、增量取出 Annex-B 编码包，不经过容器层。
 * 输入接受 {@link PixelFormat#YUV420P} 与 {@link PixelFormat#NV12}：NVENC 的 8 位主路径吃 NV12，
 * 因此 YUV420P 会在送入前把 U/V 交织成一个平面。</p>
 *
 * <p>实例持有原生编码上下文，<b>非线程安全</b>，一路编码一个实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"h264_nvenc", "nvenc"}, order = 100)
@SpiDescribe("ffmpeg h264_nvenc 硬件视频编码器")
public class H264NvencVideoEncoder implements VideoEncoder {

    /**
     * ffmpeg 编码器名。
     */
    private static final String CODEC_NAME = "h264_nvenc";

    /**
     * 未指定分辨率时的默认宽。
     */
    private static final int DEFAULT_WIDTH = 1280;

    /**
     * 未指定分辨率时的默认高。
     */
    private static final int DEFAULT_HEIGHT = 720;

    /**
     * 未指定帧率时的默认帧率。
     */
    private static final int DEFAULT_FPS = 30;

    /**
     * 未指定码率时的默认码率，单位 kbps。
     */
    private static final int DEFAULT_BITRATE_KBPS = 4000;

    /**
     * 未指定 GOP 时的默认关键帧间隔。
     */
    private static final int DEFAULT_GOP = 60;

    /**
     * 单次交付循环的上限，防止原生侧异常时死循环。
     */
    private static final int MAX_DRAIN_PER_CALL = 64;

    /**
     * 允许通过附加选项透传给 nvenc 的键，原样送入 {@code avcodec_open2}。
     *
     * <p>清单按真机实测收敛：{@code rc}（{@code constqp}/{@code cbr}/{@code vbr}…，非法值会在
     * 打开会话时直接报错）、{@code preset}（{@code p1}-{@code p7}）、{@code tune}
     * （{@code hq}/{@code ll}/{@code lossless}）、{@code delay}、{@code gpu}、
     * {@code b_adapt}、{@code spatial_aq}、{@code temporal_aq}；后三项中
     * {@code spatial_aq} 已用同一段素材量出体积变化（134665 → 130439 字节）。</p>
     *
     * <p>{@code profile} 与 {@code level} 不透传：nvenc 确实有自己的同名选项
     * （{@code av_opt_find} 查到 {@code profile} 0-3、{@code level} 0-62），但选项字典先按
     * {@code AVCodecContext} 上的同名整型字段解析，于是字符串名 {@code baseline}/{@code main}
     * 直接被拒，数值 {@code 0}/{@code 2}/{@code 52} 虽被接受却改不动 SPS——12 帧实测
     * {@code profile_idc} 恒为 77、{@code level_idc} 恒为 21。{@code cq} 同属无效键
     * （{@code rc=constqp} 下 {@code cq=10} 与 {@code cq=45} 字节数完全一致）。
     * {@code quality}、{@code cbr_padding}、{@code tier}、{@code repeat_headers}、
     * {@code scenario} 在该绑定上查无此选项。</p>
     */
    private static final List<String> PASSTHROUGH_OPTIONS =
            List.of("rc", "delay", "gpu", "b_adapt", "spatial_aq", "temporal_aq");

    /**
     * 原生编码上下文。
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
     * 行缓冲，复用以避免逐帧分配。
     */
    private byte[] lumaRow;

    /**
     * 交织用的 U 行缓冲。
     */
    private byte[] chromaU;

    /**
     * 交织用的 V 行缓冲。
     */
    private byte[] chromaV;

    /**
     * 交织后的 UV 行缓冲。
     */
    private byte[] interleaved;

    /**
     * 会话分辨率宽。
     */
    private int width;

    /**
     * 会话分辨率高。
     */
    private int height;

    /**
     * 会话帧率，同时是时基分母。
     */
    private int fps;

    /**
     * 下一帧可用的 PTS，保证单调。
     */
    private long nextPts;

    @Override
    public boolean isAvailable() {
        return NvCodecProbe.probe(CODEC_NAME, true) == null;
    }

    @Override
    public boolean supports(String format) {
        if (!isAvailable() || format == null) {
            return false;
        }
        String name = format.toLowerCase(Locale.ROOT).replace('-', '_');
        return name.startsWith("h264") || name.startsWith("avc") || name.equals("nvenc");
    }

    @Override
    public void configure(VideoCodecOptions options) {
        releaseNative();
        String reason = NvCodecProbe.probe(CODEC_NAME, true);
        if (reason != null) {
            throw new CodecException("h264_nvenc unavailable: " + reason);
        }
        VideoCodecOptions actual = options == null ? new VideoCodecOptions() : options;
        PixelFormat requested = actual.getPixelFormat();
        if (requested != null && requested != PixelFormat.YUV420P && requested != PixelFormat.NV12) {
            throw new CodecException("h264_nvenc accepts YUV420P or NV12 input, got %s", requested);
        }
        if (actual.getQpMin() > 0 || actual.getQpMax() > 0) {
            throw new CodecException("h264_nvenc ignores qmin/qmax, steer rate control with the "
                    + "rc option instead");
        }
        width = actual.getWidth() > 0 ? actual.getWidth() : DEFAULT_WIDTH;
        height = actual.getHeight() > 0 ? actual.getHeight() : DEFAULT_HEIGHT;
        fps = actual.getFps() > 0 ? Math.round(actual.getFps()) : DEFAULT_FPS;
        AVCodec codec = avcodec.avcodec_find_encoder_by_name(CODEC_NAME);
        context = avcodec.avcodec_alloc_context3(codec);
        if (context == null || context.isNull()) {
            throw new CodecException("avcodec_alloc_context3(%s) failed", CODEC_NAME);
        }
        context.width(width).height(height);
        context.pix_fmt(avutil.AV_PIX_FMT_NV12);
        context.time_base(new AVRational().num(1).den(fps));
        context.framerate(new AVRational().num(fps).den(1));
        context.gop_size(actual.getGopSize() > 0 ? actual.getGopSize() : DEFAULT_GOP);
        context.max_b_frames(0);
        int bitrateKbps = actual.getBitrateKbps() > 0 ? actual.getBitrateKbps() : DEFAULT_BITRATE_KBPS;
        context.bit_rate(bitrateKbps * 1000L);
        AVDictionary dict = new AVDictionary(null);
        avutil.av_dict_set(dict, "preset", actual.extra("preset", "p5"), 0);
        avutil.av_dict_set(dict, "tune", actual.extra("tune", "hq"), 0);
        for (String key : PASSTHROUGH_OPTIONS) {
            String value = actual.getExtra().get(key);
            if (value != null) {
                avutil.av_dict_set(dict, key, value, 0);
            }
        }
        int ret = avcodec.avcodec_open2(context, codec, dict);
        avutil.av_dict_free(dict);
        if (ret != 0) {
            releaseNative();
            throw new CodecException("avcodec_open2(%s) failed: %s", CODEC_NAME,
                    NvCodecProbe.strerror(ret));
        }
        frame = avutil.av_frame_alloc();
        frame.format(avutil.AV_PIX_FMT_NV12).width(width).height(height);
        ret = avutil.av_frame_get_buffer(frame, 0);
        if (ret != 0) {
            releaseNative();
            throw new CodecException("av_frame_get_buffer failed: %s", NvCodecProbe.strerror(ret));
        }
        packet = avcodec.av_packet_alloc();
        lumaRow = new byte[width];
        chromaU = new byte[width];
        chromaV = new byte[width];
        interleaved = new byte[width];
        nextPts = 0L;
    }

    @Override
    public List<VideoPacket> encode(VideoFrame input) {
        requireSession();
        if (input.width() != width || input.height() != height) {
            throw new CodecException("frame is %dx%d but session is %dx%d",
                    input.width(), input.height(), width, height);
        }
        if (input.pixelFormat() != PixelFormat.YUV420P && input.pixelFormat() != PixelFormat.NV12) {
            throw new CodecException("h264_nvenc needs YUV420P or NV12 frames, got %s",
                    input.pixelFormat());
        }
        if (avutil.av_frame_make_writable(frame) != 0) {
            throw new CodecException("av_frame_make_writable failed");
        }
        fillNv12(input);
        frame.pts(resolvePts(input));
        int ret = avcodec.avcodec_send_frame(context, frame);
        if (ret != 0) {
            throw new CodecException("avcodec_send_frame failed: %s", NvCodecProbe.strerror(ret));
        }
        return receivePackets();
    }

    @Override
    public List<VideoPacket> flush() {
        requireSession();
        int ret = avcodec.avcodec_send_frame(context, (AVFrame) null);
        if (ret != 0 && ret != avutil.AVERROR_EOF) {
            throw new CodecException("avcodec_send_frame(NULL) failed: %s", NvCodecProbe.strerror(ret));
        }
        return receivePackets();
    }

    @Override
    public void close() {
        releaseNative();
    }

    /**
     * 把送入帧的平面写进原生帧的 NV12 缓冲。
     *
     * @param input 输入帧
     */
    private void fillNv12(VideoFrame input) {
        copyPlane(input.plane(0), input.lineSize(0), frame.data(0), frame.linesize(0),
                input.height(), input.width());
        int chromaRows = (input.height() + 1) / 2;
        if (input.pixelFormat() == PixelFormat.NV12) {
            copyPlane(input.plane(1), input.lineSize(1), frame.data(1), frame.linesize(1),
                    chromaRows, input.width());
            return;
        }
        interleaveChroma(input, chromaRows);
    }

    /**
     * YUV420P 的 U/V 双平面交织为 NV12 的 UV 单平面。
     *
     * @param input      输入帧
     * @param chromaRows 色度行数
     */
    private void interleaveChroma(VideoFrame input, int chromaRows) {
        int chromaCols = (input.width() + 1) / 2;
        ByteBuffer u = readable(input.plane(1));
        ByteBuffer v = readable(input.plane(2));
        long uBase = u.position();
        long vBase = v.position();
        BytePointer target = frame.data(1);
        int targetStride = frame.linesize(1);
        for (int y = 0; y < chromaRows; y++) {
            readRow(u, uBase + (long) y * input.lineSize(1), chromaU, chromaCols);
            readRow(v, vBase + (long) y * input.lineSize(2), chromaV, chromaCols);
            for (int x = 0; x < chromaCols; x++) {
                interleaved[2 * x] = chromaU[x];
                interleaved[2 * x + 1] = chromaV[x];
            }
            target.position((long) y * targetStride);
            target.put(interleaved, 0, chromaCols * 2);
        }
    }

    /**
     * 按行跨距把源平面拷进目标指针。
     *
     * @param source       源缓冲
     * @param sourceStride 源行跨距
     * @param target       目标原生指针
     * @param targetStride 目标行跨距
     * @param rows         行数
     * @param rowBytes     每行有效字节
     */
    private void copyPlane(ByteBuffer source, int sourceStride, BytePointer target, int targetStride,
                           int rows, int rowBytes) {
        ByteBuffer view = readable(source);
        long base = view.position();
        for (int y = 0; y < rows; y++) {
            readRow(view, base + (long) y * sourceStride, lumaRow, rowBytes);
            target.position((long) y * targetStride);
            target.put(lumaRow, 0, rowBytes);
        }
    }

    /**
     * 取得一个整段容量可读的视图，外部位置留作行读取的基准偏移。
     *
     * @param source 平面缓冲
     * @return 只读视图
     */
    private static ByteBuffer readable(ByteBuffer source) {
        ByteBuffer view = source.duplicate();
        view.limit(view.capacity());
        return view;
    }

    /**
     * 从视图的绝对偏移读取一行。
     *
     * @param source 视图
     * @param offset 绝对偏移
     * @param target 目标数组
     * @param length 读取长度
     */
    private static void readRow(ByteBuffer source, long offset, byte[] target, int length) {
        source.slice((int) offset, length).get(target, 0, length);
    }

    /**
     * 计算送入编码器的 PTS 并推进单调游标。
     *
     * @param input 输入帧
     * @return 以会话时基表达的 PTS
     */
    private long resolvePts(VideoFrame input) {
        long pts = nextPts;
        if (input.pts() > 0) {
            pts = Math.max(pts, Math.round(input.pts() * (double) fps / avutil.AV_TIME_BASE));
        }
        nextPts = pts + 1;
        return pts;
    }

    /**
     * 取出原生侧当前可交付的编码包。
     *
     * @return 编码包列表，可能为空
     */
    private List<VideoPacket> receivePackets() {
        List<VideoPacket> packets = new ArrayList<>();
        for (int i = 0; i < MAX_DRAIN_PER_CALL; i++) {
            avcodec.av_packet_unref(packet);
            int ret = avcodec.avcodec_receive_packet(context, packet);
            if (ret != 0) {
                break;
            }
            packets.add(toPacket(packet));
        }
        return packets;
    }

    /**
     * 原生包转为 SPI 包，时间戳换算回微秒。
     *
     * @param source 原生包
     * @return SPI 包
     */
    private VideoPacket toPacket(AVPacket source) {
        int size = source.size();
        byte[] data = new byte[size];
        if (size > 0) {
            source.data().position(0);
            source.data().get(data, 0, size);
        }
        AVRational timeBase = source.time_base();
        int rate = timeBase == null || timeBase.den() == 0 ? fps : timeBase.den();
        long pts = source.pts() == avutil.AV_NOPTS_VALUE ? -1L
                : Math.round(source.pts() * (double) avutil.AV_TIME_BASE / rate);
        long dts = source.dts() == avutil.AV_NOPTS_VALUE ? -1L
                : Math.round(source.dts() * (double) avutil.AV_TIME_BASE / rate);
        boolean key = (source.flags() & avcodec.AV_PKT_FLAG_KEY) != 0;
        return VideoPacket.of(data, pts, dts, key);
    }

    /**
     * 校验会话已打开。
     */
    private void requireSession() {
        if (context == null) {
            throw new CodecException("encoder session not configured, call configure() first");
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
