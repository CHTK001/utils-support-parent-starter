package com.chua.common.support.media.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * ffmpeg 处理器 SPI 接口，定义音视频处理的核心操作。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FFmpegProcessor {

    /**
     * 视频格式转换
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param targetFormat 目标格式化，不允许为 null
     */
    void convertVideo(File input, File output, String targetFormat) throws IOException;
    /**
     * 转换Video。
     *
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param targetFormat 目标格式化，不允许为 null
     * @param options 选项，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException;

    /** 流式视频转换 */
    void convertVideo(InputStream inputStream, OutputStream outputStream,
                      String inputFormat, String outputFormat) throws IOException;

    /**
     * 音频格式转换
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param targetFormat 目标格式化，不允许为 null
     */
    void convertAudio(File input, File output, String targetFormat) throws IOException;
    /**
     * 转换Audio。
     *
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param targetFormat 目标格式化，不允许为 null
     * @param options 选项，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException;

    /**
     * 提取音频
     * @param videoInput 方法入参 videoInput
     * @param audioOutput 方法入参 audioOutput
     * @param audioFormat audio格式化，不允许为 null
     */
    void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException;

    /**
     * 截取单帧
     * @param videoInput 方法入参 videoInput
     * @param imageOutput 方法入参 imageOutput
     * @param timestamp 时间戳，不允许为 null
     */
    void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException;

    /**
     * 按间隔截取多帧
     * @param videoInput 方法入参 videoInput
     * @param outputDir output目录，不允许为 null
     * @param interval 间隔，不允许为 null
     * @param imageFormat image格式化，不允许为 null
     * @return 文件 对象
     */
    File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException;

    /**
     * 生成缩略图
     * @param videoInput 方法入参 videoInput
     * @param imageOutput 方法入参 imageOutput
     * @param width 宽度，不允许为 null
     * @param height 高度，不允许为 null
     */
    void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException;

    /**
     * 视频裁剪
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param startTime 启动时间，不允许为 null
     * @param duration 时长，不允许为 null
     */
    void trim(File input, File output, double startTime, double duration) throws IOException;

    /**
     * 视频拼接
     * @param inputs 方法入参 inputs
     * @param output 方法入参 output
     */
    void concat(File[] inputs, File output) throws IOException;

    /**
     * 视频缩放
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param width 宽度，不允许为 null
     * @param height 高度，不允许为 null
     */
    void resize(File input, File output, int width, int height) throws IOException;

    /**
     * 视频旋转（90/180/270）
     * @param input 方法入参 input
     * @param output 方法入参 output
     * @param angle 方法入参 angle
     */
    void rotate(File input, File output, int angle) throws IOException;

    /**
     * 添加水印
     * @param videoInput 方法入参 videoInput
     * @param watermarkFile watermark文件，不允许为 null
     * @param output 方法入参 output
     * @param x 方法入参 x
     * @param y 方法入参 y
     */
    void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException;

    /**
     * 视频转 GIF
     * @param videoInput 方法入参 videoInput
     * @param gifOutput 方法入参 gifOutput
     * @param startTime 启动时间，不允许为 null
     * @param duration 时长，不允许为 null
     * @param width 宽度，不允许为 null
     * @param fps 方法入参 fps
     */
    void videoToGif(File videoInput, File gifOutput, double startTime, double duration, int width, int fps) throws IOException;

    /**
     * 图片序列转视频
     * @param imageDir image目录，不允许为 null
     * @param videoOutput 方法入参 videoOutput
     * @param fps 方法入参 fps
     * @param imagePattern image模式，不允许为 null
     */
    void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException;

    /**
     * RTMP 推流
     * @param input 方法入参 input
     * @param streamUrl 流URL，不允许为 null
     * @param options 选项，不允许为 null
     */
    void pushStream(String input, String streamUrl, FFmpegOptions options) throws IOException;

    /** RTMP 推流（带帧通知回调，无返回） */
    default void pushStream(String input, String streamUrl, FFmpegOptions options,
                            Consumer<FrameInfo> callback) throws IOException {
        pushStream(input, streamUrl, options);
    }

    /** RTMP 推流（带帧回调，返回帧数据用于渲染） */
    default void pushStreamWithFrames(String input, String streamUrl, FFmpegOptions options,
                                      Consumer<FrameInfo> callback) throws IOException {
        pushStream(input, streamUrl, options);
    }

    /**
     * 拉流保存
     * @param streamUrl 流URL，不允许为 null
     * @param output 方法入参 output
     * @param duration 时长，不允许为 null
     */
    void pullStream(String streamUrl, File output, double duration) throws IOException;

    /** 拉流保存（带帧通知回调，无返回） */
    default void pullStream(String streamUrl, File output, double duration,
                            Consumer<FrameInfo> callback) throws IOException {
        pullStream(streamUrl, output, duration);
    }

    /** 拉流保存（带帧回调，返回帧数据用于渲染） */
    default void pullStreamWithFrames(String streamUrl, File output, double duration,
                                      Consumer<FrameInfo> callback) throws IOException {
        pullStream(streamUrl, output, duration);
    }

    /**
     * 获取媒体信息
     * @param input 方法入参 input
     * @return FFmpegMediaInfo 对象
     */
    FFmpegMediaInfo getMediaInfo(File input) throws IOException;

    /**
     * 获取视频时长
     * @param input 方法入参 input
     * @return 结果数值
     */
    double getDuration(File input) throws IOException;

    /**
     * 判断 ffmpeg 是否可用
     * @return 是否成功（true 表示成功）
     */
    boolean isAvailable();

    /**
     * 获取版本信息
     * @return 结果字符串
     */
    String getVersion();

    /**
     * 执行自定义 ffmpeg 命令
     * @param args 参数，不允许为 null
     * @return FFmpeg结果 对象
     */
    FFmpegResult execute(String... args) throws IOException;
}
