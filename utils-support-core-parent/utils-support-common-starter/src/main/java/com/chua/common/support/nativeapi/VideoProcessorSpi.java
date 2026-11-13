package com.chua.common.support.nativeapi;

/**
 * 视频处理原生库 SPI。
 *
 * <p>由 {@code utils-support-native-video-processor} 提供实现，对应
 * {@code video_processor} 的 HLS 转码与版本查询。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface VideoProcessorSpi extends NativeService {

    /**
     * 原生库是否已加载。
     *
     * @return 已加载返回 {@code true}
     */
    boolean isLoaded();

    @Override
    default boolean available() {
        return isLoaded();
    }

    /**
     * 确保原生库已加载，未加载时抛异常。
     */
    void ensureLoaded();

    /**
     * 转码为 HLS。
     *
     * @param inputPath 输入路径
     * @param outputDir 输出目录
     * @return 成功返回 {@code true}
     */
    boolean transcodeToHls(String inputPath, String outputDir);

    /**
     * 原生库版本。
     *
     * @return 版本串
     */
    String getVersion();

    /**
     * 原生库加载失败原因。
     *
     * @return 失败异常；加载成功返回 {@code null}
     */
    Throwable getLoadError();
}
