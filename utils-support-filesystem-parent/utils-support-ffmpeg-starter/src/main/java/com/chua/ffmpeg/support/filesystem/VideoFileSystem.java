package com.chua.ffmpeg.support.filesystem;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
* 视频文件系统 SPI 实现。
*
* <p>基于 FFmpeg 实现视频文件的元数据读取与格式转换。
* 底层通过 {@link FFmpegProcessor} SPI 进行实际的 ffmpeg 操作。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("video")
public class VideoFileSystem implements FileSystem {

    /** 处理器 */
    private final FFmpegProcessor processor;

    /** 创建 视频文件系统 实例 */
    public VideoFileSystem() {
        FFmpegProcessor p = null;
        try {
            p = ServiceProvider.of(FFmpegProcessor.class).getExtension("jaffree");
        } catch (Exception e) {
 // 无可用 ffmpeg 实现
        }
        this.processor = p;
    }

    @Override
    /** 获取类型 */
    public String getType() {
        return "video";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new VideoReadBuilder(file, processor);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new VideoWriteBuilder(file, processor);
    }

    /**
    * 获取处理器
    *
    * @return 获取处理器的结果
     */
    public FFmpegProcessor getProcessor() {
        return processor;
    }
}
