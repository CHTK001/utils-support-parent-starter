package com.chua.ffmpeg.support.codec;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NVIDIA 硬编解码可用性探针。
 *
 * <p>{@code avcodec_find_encoder_by_name("h264_nvenc")} 只要编译期带上了 NVENC 就会返回非空，
 * 与本机是否插着 NVIDIA 显卡、驱动是否可用无关。因此这里以"真的打开一次 64x64 会话"作为判据，
 * 结果按编解码器名缓存，避免每次调用都触碰原生上下文。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class NvCodecProbe {

    /**
     * 探针会话边长。
     */
    private static final int PROBE_SIZE = 64;

    /**
     * 探针会话帧率。
     */
    private static final int PROBE_FPS = 25;

    /**
     * 探测成功的编解码器名集合。
     */
    private static final Set<String> USABLE = ConcurrentHashMap.newKeySet();

    /**
     * 编解码器名到不可用原因的映射，无键即表示可用。
     */
    private static final Map<String, String> REASONS = new ConcurrentHashMap<>();

    /**
     * 工具类，禁止实例化。
     */
    private NvCodecProbe() {
    }

    /**
     * 探测一次真实会话。
     *
     * @param codecName 编解码器名，如 {@code h264_nvenc}
     * @param encoder   {@code true} 探测编码器，{@code false} 探测解码器
     * @return 可用返回 {@code null}，否则返回不可用原因
     */
    static String probe(String codecName, boolean encoder) {
        String reason = REASONS.get(codecName);
        if (reason != null) {
            return reason;
        }
        if (USABLE.contains(codecName)) {
            return null;
        }
        String computed = openSession(codecName, encoder);
        if (computed == null) {
            USABLE.add(codecName);
            return null;
        }
        REASONS.put(codecName, computed);
        return computed;
    }

    /**
     * 打开并立即关闭一个最小会话。
     *
     * @param codecName 编解码器名
     * @param encoder   是否编码器
     * @return 成功返回 {@code null}，失败返回原因描述
     */
    private static String openSession(String codecName, boolean encoder) {
        AVCodec codec = encoder ? avcodec.avcodec_find_encoder_by_name(codecName)
                : avcodec.avcodec_find_decoder_by_name(codecName);
        if (codec == null || codec.isNull()) {
            return "ffmpeg build has no codec named " + codecName;
        }
        AVCodecContext context = avcodec.avcodec_alloc_context3(codec);
        if (context == null || context.isNull()) {
            return "avcodec_alloc_context3 returned null";
        }
        AVDictionary options = new AVDictionary(null);
        try {
            context.width(PROBE_SIZE);
            context.height(PROBE_SIZE);
            context.pix_fmt(avutil.AV_PIX_FMT_NV12);
            context.time_base(new AVRational().num(1).den(PROBE_FPS));
            if (encoder) {
                context.framerate(new AVRational().num(PROBE_FPS).den(1));
                context.gop_size(PROBE_FPS * 2);
                context.max_b_frames(0);
                avutil.av_dict_set(options, "preset", "p1", 0);
            }
            int ret = avcodec.avcodec_open2(context, codec, options);
            if (ret != 0) {
                return codecName + " cannot open session: " + strerror(ret);
            }
            return null;
        } catch (Throwable t) {
            return codecName + " probe threw " + t;
        } finally {
            avutil.av_dict_free(options);
            avcodec.avcodec_free_context(context);
        }
    }

    /**
     * 把 ffmpeg 错误码翻译成可读文本。
     *
     * @param code 原生返回码
     * @return 错误描述
     */
    static String strerror(int code) {
        byte[] buffer = new byte[256];
        avutil.av_strerror(code, buffer, buffer.length);
        int length = 0;
        while (length < buffer.length && buffer[length] != 0) {
            length++;
        }
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }
}
