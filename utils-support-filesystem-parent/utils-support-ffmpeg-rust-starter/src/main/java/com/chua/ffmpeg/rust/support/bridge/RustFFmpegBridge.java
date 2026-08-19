package com.chua.ffmpeg.rust.support.bridge;

import com.chua.nativeffmpeg.support.NativeFFmpeg;
import com.chua.nativevideocodec.support.NativeVideoCodec;
import com.chua.common.support.media.ffmpeg.FrameInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * RustFFmpegProcessor / VideoEncoder 与原生 cdylib 之间的轻量桥接。
 *
 * <p>加载的动态库与 native-video-codec 和 native-crypto 使用的相同。</p>
 *
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

    // ==================== 推流/拉流桥接 ====================

    /**
     * 检查 NativeFFmpeg 推流/拉流库是否已加载。
     *
     * @return true 表示已加载
     */
    public static boolean isStreamLoaded() {
        return NativeFFmpeg.isLoaded();
    }

    /**
     * RTMP 推流（无回调）。
     *
     * @param inputUrl   输入 URL 或文件路径
     * @param streamUrl  推流地址
     * @param videoCodec 视频编码器名称，null 使用默认
     * @param audioCodec 音频编码器名称，null 使用默认
     * @param width      视频宽度，0 使用源
     * @param height     视频高度，0 使用源
     * @param fps        帧率，0 使用源
     * @return 0 表示成功
     */
    public static int pushStream(String inputUrl, String streamUrl,
                                  String videoCodec, String audioCodec,
                                  int width, int height, int fps) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.pushStream(inputUrl, streamUrl, videoCodec, audioCodec, width, height, fps);
    }

    /**
     * RTMP 推流（带帧通知回调）。
     *
     * @param inputUrl   输入 URL 或文件路径
     * @param streamUrl  推流地址
     * @param videoCodec 视频编码器名称，null 使用默认
     * @param audioCodec 音频编码器名称，null 使用默认
     * @param width      视频宽度，0 使用源
     * @param height     视频高度，0 使用源
     * @param fps        帧率，0 使用源
     * @param callback   帧通知回调
     * @return 0 表示成功
     */
    public static int pushStreamWithCallback(String inputUrl, String streamUrl,
                                              String videoCodec, String audioCodec,
                                              int width, int height, int fps,
                                              Consumer<FrameInfo> callback) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        NativeFFmpeg.FrameCallback jniCallback = (frameNumber, timestampMs, w, h, codec, f, keyFrame) -> {
            if (callback != null) {
                FrameInfo info = new FrameInfo();
                info.setFrameNumber(frameNumber);
                info.setTimestampMs(timestampMs);
                info.setWidth(w);
                info.setHeight(h);
                info.setCodec(codec);
                info.setFps(f);
                info.setKeyFrame(keyFrame);
                callback.accept(info);
            }
        };
        return NativeFFmpeg.pushStreamWithCallback(inputUrl, streamUrl, videoCodec, audioCodec,
                width, height, fps, jniCallback);
    }

    /**
     * RTMP 拉流保存（无回调）。
     *
     * @param streamUrl  拉流地址
     * @param outputPath 输出文件路径
     * @param duration   拉流时长（秒），0 表示持续拉流
     * @return 0 表示成功
     */
    public static int pullStream(String streamUrl, String outputPath, double duration) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.pullStream(streamUrl, outputPath, duration);
    }

    /**
     * RTMP 拉流保存（带帧通知回调）。
     *
     * @param streamUrl  拉流地址
     * @param outputPath 输出文件路径
     * @param duration   拉流时长（秒），0 表示持续拉流
     * @param callback   帧通知回调
     * @return 0 表示成功
     */
    public static int pullStreamWithCallback(String streamUrl, String outputPath,
                                              double duration,
                                              Consumer<FrameInfo> callback) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        NativeFFmpeg.FrameCallback jniCallback = (frameNumber, timestampMs, w, h, codec, f, keyFrame) -> {
            if (callback != null) {
                FrameInfo info = new FrameInfo();
                info.setFrameNumber(frameNumber);
                info.setTimestampMs(timestampMs);
                info.setWidth(w);
                info.setHeight(h);
                info.setCodec(codec);
                info.setFps(f);
                info.setKeyFrame(keyFrame);
                callback.accept(info);
            }
        };
        return NativeFFmpeg.pullStreamWithCallback(streamUrl, outputPath, duration, jniCallback);
    }

    /**
     * 获取媒体文件时长。
     *
     * @param inputUrl 输入 URL 或文件路径
     * @return 时长（秒），失败返回 -1
     */
    public static double getStreamDuration(String inputUrl) {
        if (!NativeFFmpeg.isLoaded()) {
            return -1;
        }
        return NativeFFmpeg.getDuration(inputUrl);
    }

    // ==================== 文件转码桥接 ====================

    /**
     * 通用文件转码。
     *
     * @param inputUrl    输入文件路径
     * @param outputPath  输出文件路径
     * @param videoCodec  视频编码器，null 使用默认
     * @param audioCodec  音频编码器，null 使用默认
     * @param width       视频宽度，0 使用源
     * @param height      视频高度，0 使用源
     * @param fps         帧率，0 使用源
     * @param startTime   起始时间（秒），0 从头开始
     * @param duration    持续时长（秒），0 到结尾
     * @param removeVideo 是否移除视频流
     * @param removeAudio 是否移除音频流
     * @return 0 表示成功
     */
    public static int convertFile(String inputUrl, String outputPath,
                                   String videoCodec, String audioCodec,
                                   int width, int height, int fps,
                                   double startTime, double duration,
                                   boolean removeVideo, boolean removeAudio) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.convertFile(inputUrl, outputPath, videoCodec, audioCodec,
                width, height, fps, startTime, duration, removeVideo, removeAudio);
    }

    /**
     * 截取单帧保存为图片。
     *
     * @param inputUrl   输入文件路径
     * @param timestampMs 时间戳（毫秒）
     * @param outputPath  输出图片路径
     * @return 0 表示成功
     */
    public static int captureFrame(String inputUrl, long timestampMs, String outputPath) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.captureFrame(inputUrl, timestampMs, outputPath);
    }

    /**
     * 拼接多个媒体文件。
     *
     * @param inputPaths 输入文件路径列表，以分号分隔
     * @param outputPath 输出文件路径
     * @return 0 表示成功
     */
    public static int concatFiles(String inputPaths, String outputPath) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.concatFiles(inputPaths, outputPath);
    }

    /**
     * 获取媒体文件信息（JSON 格式）。
     *
     * @param inputUrl 输入 URL 或文件路径
     * @return JSON 格式的媒体信息，失败返回 null
     */
    public static String getStreamMediaInfo(String inputUrl) {
        if (!NativeFFmpeg.isLoaded()) {
            return null;
        }
        return NativeFFmpeg.getMediaInfo(inputUrl);
    }

    // ==================== 视频旋转桥接 ====================

    /**
     * 视频旋转（90/180/270度）。
     *
     * @param inputUrl   输入文件路径
     * @param outputPath 输出文件路径
     * @param angle      旋转角度（90/180/270）
     * @return 0 表示成功
     */
    public static int rotate(String inputUrl, String outputPath, int angle) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.rotate(inputUrl, outputPath, angle);
    }

    // ==================== 添加水印桥接 ====================

    /**
     * 添加图片水印。
     *
     * @param inputUrl      输入视频文件路径
     * @param watermarkPath 水印图片文件路径
     * @param outputPath    输出文件路径
     * @param x             水印 X 坐标
     * @param y             水印 Y 坐标
     * @return 0 表示成功
     */
    public static int addWatermark(String inputUrl, String watermarkPath,
                                   String outputPath, int x, int y) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.addWatermark(inputUrl, watermarkPath, outputPath, x, y);
    }

    // ==================== 图片序列转视频桥接 ====================

    /**
     * 图片序列转视频。
     *
     * @param imageDir     图片目录路径
     * @param outputPath   输出视频文件路径
     * @param fps          帧率
     * @param imagePattern 图片文件名匹配模式（如 "frame_%06d.jpg"）
     * @return 0 表示成功
     */
    public static int imagesToVideo(String imageDir, String outputPath,
                                    int fps, String imagePattern) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.imagesToVideo(imageDir, outputPath, fps, imagePattern);
    }
}
