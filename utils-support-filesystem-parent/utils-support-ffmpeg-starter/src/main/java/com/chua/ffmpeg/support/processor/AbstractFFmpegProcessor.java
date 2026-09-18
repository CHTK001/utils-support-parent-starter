package com.chua.ffmpeg.support.processor;

import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;

import java.io.File;
import java.io.IOException;

/**
* ffmpeg 处理器抽象基类，提供 ffmpeg 可执行文件查找和通用参数构建逻辑。
*
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractFFmpegProcessor implements FFmpegProcessor {

    /**
    * 是否已初始化可用
    */
    protected volatile boolean available;

    /**
    * 初始化失败原因
    */
    protected String loadError;

    /**
    * ffmpeg 可执行文件路径
    */
    protected File ffmpegFile;

    /**
    * ffprobe 可执行文件路径
    */
    protected File ffprobeFile;

    /**
    * 构造处理器并自动查找 ffmpeg。
    */
    protected AbstractFFmpegProcessor() {
        try {
            locateFFmpeg();
        } catch (Exception e) {
            available = false;
            loadError = e.getMessage();
        }
    }

    /**
    * 在系统 路径 和常见路径中查找 ffmpeg。
    */
    protected void locateFFmpeg() {
        String os = System.getProperty("os.name").toLowerCase();
        String ffmpegExe = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";
        String ffprobeExe = os.contains("win") ? "ffprobe.exe" : "ffprobe";

 // 查找系统 路径
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File ffmpeg = new File(dir, ffmpegExe);
                File ffprobe = new File(dir, ffprobeExe);
                if (ffmpeg.exists() && ffprobe.exists()) {
                    ffmpegFile = ffmpeg;
                    ffprobeFile = ffprobe;
                    available = true;
                    return;
                }
            }
        }

        // 查找常见路径
        String[] commonPaths = {
            "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin",
            "C:\\ffmpeg\\bin", "C:\\Program Files\\ffmpeg\\bin"
        };
        for (String path : commonPaths) {
            File fm = new File(path, ffmpegExe);
            File fp = new File(path, ffprobeExe);
            if (fm.exists() && fp.exists()) {
                ffmpegFile = fm;
                ffprobeFile = fp;
                available = true;
                return;
            }
        }

        available = false;
        loadError = "FFmpeg not found in PATH or common locations";
    }

    /**
    * 解析文件扩展名作为目标格式。
    * @param output 输出
    * @return resolve格式化的结果
    */
    protected String resolveFormat(File output) {
        String name = output.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "mp4";
    }

    /**
    * 将 ffmpeg期权 转换为命令行参数字符串数组。
    * @param opts opts
    * @return 构建codec参数的结果
    */
    protected String[] buildCodecArgs(FFmpegOptions opts) {
        java.util.List<String> args = new java.util.ArrayList<>();
        if (opts == null) {
            return args.toArray(new String[0]);
        }
        if (opts.getVideoCodec() != null) {
            args.add("-c:v");
            args.add(opts.getVideoCodec());
        }
        if (opts.getAudioCodec() != null) {
            args.add("-c:a");
            args.add(opts.getAudioCodec());
        }
        if (opts.getVideoBitrate() != null) {
            args.add("-b:v");
            args.add(opts.getVideoBitrate());
        }
        if (opts.getAudioBitrate() != null) {
            args.add("-b:a");
            args.add(opts.getAudioBitrate());
        }
        if (opts.getFps() != null) {
            args.add("-r");
            args.add(String.valueOf(opts.getFps()));
        }
        if (opts.getWidth() != null && opts.getHeight() != null) {
            args.add("-vf");
            args.add("scale=" + opts.getWidth() + ":" + opts.getHeight());
        }
        if (opts.getAudioSampleRate() != null) {
            args.add("-ar");
            args.add(String.valueOf(opts.getAudioSampleRate()));
        }
        if (opts.getAudioChannels() != null) {
            args.add("-ac");
            args.add(String.valueOf(opts.getAudioChannels()));
        }
        if (opts.getQuality() != null) {
            args.add("-crf");
            args.add(String.valueOf(opts.getQuality()));
        }
        if (opts.getPreset() != null) {
            args.add("-preset");
            args.add(opts.getPreset());
        }
        if (opts.isOverwrite()) { args.add("-y"); }
        if (opts.isMute()) { args.add("-an"); }
        if (opts.isRemoveVideo()) { args.add("-vn"); }
        return args.toArray(new String[0]);
    }

    @Override
    /** 是否可用 */
    public boolean isAvailable() { return available; }
}
