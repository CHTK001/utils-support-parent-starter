package com.chua.common.support.media.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * FFmpeg 处理器 SPI 接口，定义音视频处理的核心操作。
 *
 * @author CH
 * @since 1.0.0
 */
public interface FFmpegProcessor {

    /** 视频格式转换 */
    void convertVideo(File input, File output, String targetFormat) throws IOException;
    void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException;

    /** 流式视频转换 */
    void convertVideo(InputStream inputStream, OutputStream outputStream,
                      String inputFormat, String outputFormat) throws IOException;

    /** 音频格式转换 */
    void convertAudio(File input, File output, String targetFormat) throws IOException;
    void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException;

    /** 提取音频 */
    void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException;

    /** 截取单帧 */
    void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException;

    /** 按间隔截取多帧 */
    File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException;

    /** 生成缩略图 */
    void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException;

    /** 视频裁剪 */
    void trim(File input, File output, double startTime, double duration) throws IOException;

    /** 视频拼接 */
    void concat(File[] inputs, File output) throws IOException;

    /** 视频缩放 */
    void resize(File input, File output, int width, int height) throws IOException;

    /** 视频旋转（90/180/270） */
    void rotate(File input, File output, int angle) throws IOException;

    /** 添加水印 */
    void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException;

    /** 视频转 GIF */
    void videoToGif(File videoInput, File gifOutput, double startTime, double duration, int width, int fps) throws IOException;

    /** 图片序列转视频 */
    void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException;

    /** RTMP 推流 */
    void pushStream(String input, String streamUrl, FFmpegOptions options) throws IOException;

    /** 拉流保存 */
    void pullStream(String streamUrl, File output, double duration) throws IOException;

    /** 获取媒体信息 */
    FFmpegMediaInfo getMediaInfo(File input) throws IOException;

    /** 获取视频时长 */
    double getDuration(File input) throws IOException;

    /** 判断 FFmpeg 是否可用 */
    boolean isAvailable();

    /** 获取版本信息 */
    String getVersion();

    /** 执行自定义 FFmpeg 命令 */
    FFmpegResult execute(String... args) throws IOException;
}
