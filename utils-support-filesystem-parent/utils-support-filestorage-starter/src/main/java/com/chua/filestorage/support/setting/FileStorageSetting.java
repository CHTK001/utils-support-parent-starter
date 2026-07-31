package com.chua.filestorage.support.setting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件存储全局配置。
 *
 * <p>包含开关、缓存配置、水印配置，以及各 SPI 的实现选择。</p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileStorageSetting {

    /**
     * 是否启用预览功能（总开关）。
     */
    @Builder.Default
    private boolean openPreview = true;

    /**
     * 是否启用下载功能（总开关）。
     */
    @Builder.Default
    private boolean openDownload = true;

    /**
     * 是否启用 Range 断点续传。
     */
    @Builder.Default
    private boolean openRange = true;

    /**
     * 是否启用 webjars 资源访问。
     */
    @Builder.Default
    private boolean openWebjars = true;

    /**
     * 是否启用远程文件访问。
     */
    @Builder.Default
    private boolean openRemoteFile = false;

    /**
     * 是否启用闪图（一次性预览/下载）功能。
     */
    @Builder.Default
    private boolean openFlash = true;

    /**
     * 缓存相关配置。
     */
    @Builder.Default
    private FileStorageCacheSetting cache = new FileStorageCacheSetting();

    /**
     * 水印配置。
     */
    @Builder.Default
    private FileStorageWatermarkSetting watermark = new FileStorageWatermarkSetting();

    /**
     * FileStorageFileSetting SPI 实现名称。
     * <p>默认为空（使用 JDK 默认实现）。
     * 可设置为 {@code "native"} 使用 Rust 实现。</p>
     */
    private String fileSettingKey;

    /**
     * FileStorageFilterSetting SPI 实现名称。
     * <p>默认为空（使用 JDK 默认实现）。
     * 可设置为 {@code "native"} 使用 Rust 实现。</p>
     */
    private String filterSettingKey;

    /**
     * ImageFilter SPI 实现名称。
     * <p>默认为空（按 SPI 加载顺序）。
     * 建议设置为 {@code "native"} 使用 Rust 高性能实现。</p>
     */
    private String imageFilterKey;

    /**
     * 是否启用全局图片滤镜（基于 FileStorageFilterSetting）。
     */
    @Builder.Default
    private boolean imageFilterEnabled = false;
}
