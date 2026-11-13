package com.chua.common.support.nativeapi;

/**
 * FFmpeg Rust native 库 SPI。
 *
 * <p>由 {@code utils-support-native-ffmpeg} 提供实现，覆盖推流、拉流、转码、
 * 截帧、拼接、媒体信息与帧回调。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface FFmpegNativeSpi extends NativeService {

    /**
     * 帧回调。
     */
    @FunctionalInterface
    interface FrameCallback {

        /**
         * 收到一帧。
         *
         * @param frameNumber 帧号
         * @param timestampMs 时间戳（毫秒）
         * @param width       宽
         * @param height      高
         * @param codec       编码
         * @param fps         帧率
         * @param keyFrame    是否关键帧
         */
        void onFrame(long frameNumber, long timestampMs, int width, int height,
                     String codec, double fps, boolean keyFrame);
    }

    /**
     * 原生库加载失败原因。
     *
     * @return 失败异常；加载成功返回 {@code null}
     */
    Throwable getLoadError();

    @Override
    default boolean available() {
        return getLoadError() == null;
    }

    /**
     * 原生库版本。
     *
     * @return 版本串
     */
    String getVersion();

    /**
     * 推流。
     *
     * @param inputUrl   输入地址
     * @param streamUrl  推流地址
     * @param videoCodec 视频编码
     * @param audioCodec 音频编码
     * @param width      宽
     * @param height     高
     * @param fps        帧率
     * @return 0 表示成功
     */
    int pushStream(String inputUrl, String streamUrl, String videoCodec, String audioCodec,
                   int width, int height, int fps);

    /**
     * 带帧回调的推流。
     *
     * @param inputUrl   输入地址
     * @param streamUrl  推流地址
     * @param videoCodec 视频编码
     * @param audioCodec 音频编码
     * @param width      宽
     * @param height     高
     * @param fps        帧率
     * @param callback   帧回调
     * @return 0 表示成功
     */
    int pushStreamWithCallback(String inputUrl, String streamUrl, String videoCodec,
                               String audioCodec, int width, int height, int fps,
                               FrameCallback callback);

    /**
     * 拉流。
     *
     * @param streamUrl  流地址
     * @param outputPath 输出路径
     * @param duration   时长（秒）
     * @return 0 表示成功
     */
    int pullStream(String streamUrl, String outputPath, double duration);

    /**
     * 带帧回调的拉流。
     *
     * @param streamUrl  流地址
     * @param outputPath 输出路径
     * @param duration   时长（秒）
     * @param callback   帧回调
     * @return 0 表示成功
     */
    int pullStreamWithCallback(String streamUrl, String outputPath, double duration,
                               FrameCallback callback);

    /**
     * 读取媒体时长。
     *
     * @param inputUrl 输入地址
     * @return 时长（秒）；失败返回负数
     */
    double getDuration(String inputUrl);

    /**
     * 转码文件。
     *
     * @param inputUrl    输入地址
     * @param outputPath  输出路径
     * @param videoCodec  视频编码
     * @param audioCodec  音频编码
     * @param width       宽
     * @param height      高
     * @param fps         帧率
     * @param startTime   起始时间（秒）
     * @param duration    时长（秒）
     * @param removeVideo 是否移除视频
     * @param removeAudio 是否移除音频
     * @return 0 表示成功
     */
    int convertFile(String inputUrl, String outputPath, String videoCodec, String audioCodec,
                    int width, int height, int fps, double startTime, double duration,
                    boolean removeVideo, boolean removeAudio);

    /**
     * 截取一帧。
     *
     * @param inputUrl    输入地址
     * @param timestampMs 时间戳（毫秒）
     * @param outputPath  输出路径
     * @return 0 表示成功
     */
    int captureFrame(String inputUrl, long timestampMs, String outputPath);

    /**
     * 拼接文件。
     *
     * @param inputPaths 输入路径列表（原生约定的分隔格式）
     * @param outputPath 输出路径
     * @return 0 表示成功
     */
    int concatFiles(String inputPaths, String outputPath);

    /**
     * 读取媒体信息。
     *
     * @param inputUrl 输入地址
     * @return JSON 媒体信息
     */
    String getMediaInfo(String inputUrl);

    /**
     * 旋转。
     *
     * @param inputUrl   输入地址
     * @param outputPath 输出路径
     * @param angle      角度
     * @return 0 表示成功
     */
    int rotate(String inputUrl, String outputPath, int angle);

    /**
     * 加水印。
     *
     * @param inputUrl     输入地址
     * @param watermarkPath 水印图片
     * @param outputPath   输出路径
     * @param x            坐标 X
     * @param y            坐标 Y
     * @return 0 表示成功
     */
    int addWatermark(String inputUrl, String watermarkPath, String outputPath, int x, int y);

    /**
     * 图片序列转视频。
     *
     * @param imageDir     图片目录
     * @param outputPath   输出路径
     * @param fps          帧率
     * @param imagePattern 图片匹配模式
     * @return 0 表示成功
     */
    int imagesToVideo(String imageDir, String outputPath, int fps, String imagePattern);
}
