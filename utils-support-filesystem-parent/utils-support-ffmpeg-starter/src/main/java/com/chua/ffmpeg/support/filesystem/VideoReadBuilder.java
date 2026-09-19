package com.chua.ffmpeg.support.filesystem;

import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.media.ffmpeg.FFmpegMediaInfo;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;

import java.io.File;
import java.util.List;

/**
 * 视频文件读取构建器。
 *
 * <p>基于 FFmpeg 读取视频文件的元数据信息。
 * 通过 {@link #info()} 获取编码、分辨率、时长等信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoReadBuilder extends ReadBuilder {

    /** 处理器 */
    private final FFmpegProcessor processor;

    /**
    * 创建 视频读取构建器 实例
    * @param file 文件
    * @param processor ffmpeg处理器
    * @param processor 处理器
    */
    public VideoReadBuilder(File file, FFmpegProcessor processor) {
        super(file);
        this.processor = processor;
    }

    @Override
    /** with字符集 */
    public VideoReadBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    /**
    * 获取视频文件的完整媒体信息。
    *
    * @return 媒体信息（包含视频/音频流详情）
    */
    public FFmpegMediaInfo info() {
        try {
            if (processor != null) {
                return processor.getMediaInfo(file);
            }
        } catch (Exception e) {
            // 读取失败返回空信息
        }
        return new FFmpegMediaInfo();
    }

    /**
     * 获取视频时长（秒）。
     *
     * @return 视频时长
     */
    public double duration() {
        try {
            if (processor != null) {
                return processor.getDuration(file);
            }
        } catch (Exception e) {
            // 读取失败返回 0
        }
        return 0;
    }
}
