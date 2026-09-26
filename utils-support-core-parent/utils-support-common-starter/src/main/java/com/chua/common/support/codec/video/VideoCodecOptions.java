package com.chua.common.support.codec.video;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频编解码参数 —— 打开编码 / 解码会话前写入的配置载体。
 *
 * <p>各实现只读取自身支持的字段，未识别字段落入 {@link #getExtra()}，
 * 由实现按底层库的选项名透传（如 NVENC 的 {@code preset}、{@code tune}）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class VideoCodecOptions {

    /**
     * 编解码器名，如 {@code h264_nvenc}、{@code h264_cuvid}。
     */
    private String codecName;

    /**
     * 输出分辨率宽，单位像素；0 表示沿用会话已有值。
     */
    private int width;

    /**
     * 输出分辨率高，单位像素；0 表示沿用会话已有值。
     */
    private int height;

    /**
     * 帧率，分子/分母以浮点表达；0 表示不限定。
     */
    private float fps;

    /**
     * 目标码率，单位 kbps；0 表示由实现决定。
     */
    private int bitrateKbps;

    /**
     * 量化下限（QP）；0 表示使用底层库默认值。
     */
    private int qpMin;

    /**
     * 量化上限（QP）；0 表示使用底层库默认值。
     */
    private int qpMax;

    /**
     * 关键帧间隔（GOP），单位帧；0 表示使用底层库默认值。
     */
    private int gopSize;

    /**
     * 输入像素格式；{@code null} 表示由实现决定。
     */
    private PixelFormat pixelFormat;

    /**
     * 实现相关的附加选项。
     */
    private final Map<String, String> extra = new LinkedHashMap<>();

    /**
     * 读取附加选项。
     *
     * @param key          选项名
     * @param defaultValue 缺省值
     * @return 选项值，未设置时返回 {@code defaultValue}
     */
    public String extra(String key, String defaultValue) {
        return extra.getOrDefault(key, defaultValue);
    }

    /**
     * 设置附加选项。
     *
     * @param key   选项名
     * @param value 选项值
     * @return 当前配置对象，便于链式调用
     */
    public VideoCodecOptions withExtra(String key, String value) {
        extra.put(key, value);
        return this;
    }
}
