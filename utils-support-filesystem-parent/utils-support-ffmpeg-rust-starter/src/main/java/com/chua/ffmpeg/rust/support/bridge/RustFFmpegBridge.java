package com.chua.ffmpeg.rust.support.bridge;

import com.chua.nativevideocodec.support.NativeVideoCodec;
import lombok.extern.slf4j.Slf4j;

/**
 * RustFFmpegProcessor / VideoEncoder 与原生 cdylib 之间的轻量桥接。
 *
 * <p>加载的动态库与 native-video-codec 和 native-crypto 使用的相同。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RustFFmpegBridge {

    private RustFFmpegBridge() {
    }

    /**
     * 检查原生库是否已加载。
     *
     * @return true 表示已加载
     */
    public static boolean isLoaded() {
        try {
            NativeVideoCodec.getVersion();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 获取原生库加载错误。
     *
     * @return 加载异常，加载成功返回 null
     */
    public static Throwable getLoadError() {
        try {
            NativeVideoCodec.getVersion();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /**
     * 获取原生库版本字符串。
     *
     * @return 版本字符串
     */
    public static String getVersion() {
        return "native-video-codec-" + NativeVideoCodec.getVersion();
    }

    /**
     * H.264 编码一帧。
     *
     * @param bgr24 BGR24 格式像素数据
     * @param width 视频宽度
     * @param height 视频高度
     * @param fps 帧率
     * @return 编码后的字节数组
     */
    public static byte[] h264Encode(byte[] bgr24, int width, int height, int fps) {
        long encoder = NativeVideoCodec.h264EncoderCreate(width, height, Math.max(1, fps), 23, 1, 1);
        if (encoder == 0) {
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h264Encode(encoder, bgr24, width, height);
        } finally {
            NativeVideoCodec.h264EncoderFree(encoder);
        }
    }

    /**
     * H.265 编码一帧。
     *
     * @param bgr24 BGR24 格式像素数据
     * @param width 视频宽度
     * @param height 视频高度
     * @param fps 帧率
     * @return 编码后的字节数组
     */
    public static byte[] h265Encode(byte[] bgr24, int width, int height, int fps) {
        long encoder = NativeVideoCodec.h265EncoderCreate(width, height, Math.max(1, fps), 23, 1, 1);
        if (encoder == 0) {
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h265Encode(encoder, bgr24, width, height);
        } finally {
            NativeVideoCodec.h265EncoderFree(encoder);
        }
    }

    /**
     * H.266 编码一帧。
     *
     * @param bgr24 BGR24 格式像素数据
     * @param width 视频宽度
     * @param height 视频高度
     * @param fps 帧率
     * @return 编码后的字节数组
     */
    public static byte[] h266Encode(byte[] bgr24, int width, int height, int fps) {
        long encoder = NativeVideoCodec.h266EncoderCreate(width, height, Math.max(1, fps), 23, 1, 1);
        if (encoder == 0) {
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h266Encode(encoder, bgr24, width, height);
        } finally {
            NativeVideoCodec.h266EncoderFree(encoder);
        }
    }

    /**
     * H.264 解码一包数据。
     *
     * @param packet 编码数据包
     * @param width 视频宽度
     * @param height 视频高度
     * @return 解码后的字节数组
     */
    public static byte[] h264Decode(byte[] packet, int width, int height) {
        long decoder = NativeVideoCodec.h264DecoderCreate(width, height);
        if (decoder == 0) {
            return new byte[0];
        }
        try {
            byte[] decoded = NativeVideoCodec.h264Decode(decoder, packet, packet == null ? 0 : packet.length);
            return decoded == null ? new byte[0] : decoded;
        } finally {
            NativeVideoCodec.h264DecoderFree(decoder);
        }
    }

    /**
     * H.265 解码一包数据。
     *
     * @param packet 编码数据包
     * @param width 视频宽度
     * @param height 视频高度
     * @return 解码后的字节数组
     */
    public static byte[] h265Decode(byte[] packet, int width, int height) {
        long decoder = NativeVideoCodec.h265DecoderCreate(width, height);
        if (decoder == 0) {
            return new byte[0];
        }
        try {
            byte[] decoded = NativeVideoCodec.h265Decode(decoder, packet, packet == null ? 0 : packet.length);
            return decoded == null ? new byte[0] : decoded;
        } finally {
            NativeVideoCodec.h265DecoderFree(decoder);
        }
    }

    /**
     * H.266 解码一包数据。
     *
     * @param packet 编码数据包
     * @param width 视频宽度
     * @param height 视频高度
     * @return 解码后的字节数组
     */
    public static byte[] h266Decode(byte[] packet, int width, int height) {
        long decoder = NativeVideoCodec.h266DecoderCreate(width, height);
        if (decoder == 0) {
            return new byte[0];
        }
        try {
            byte[] decoded = NativeVideoCodec.h266Decode(decoder, packet, packet == null ? 0 : packet.length);
            return decoded == null ? new byte[0] : decoded;
        } finally {
            NativeVideoCodec.h266DecoderFree(decoder);
        }
    }
}
