package com.chua.filestorage.support.setting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件存储缓存配置。
 *
 * <p>控制 PDF 转换缓存、闪图缓存、内存缓存等行为。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileStorageCacheSetting {

    /**
     * PDF 转换缓存目录。
     * <p>默认为 {@code ${java.io.tmpdir}/filestorage-pdf-cache}</p>
     */
    @Builder.Default
    private String pdfCacheDir = System.getProperty("java.io.tmpdir") + "/filestorage-pdf-cache";

    /**
     * 闪图 0 字节 marker 文件目录。
     * <p>默认为 {@code ${java.io.tmpdir}/filestorage-flash}</p>
     */
    @Builder.Default
    private String flashDir = System.getProperty("java.io.tmpdir") + "/filestorage-flash";

    /**
     * 缓存 TTL（秒）。
     * <p>默认 86400（1 天）。0 表示永不过期。</p>
     */
    @Builder.Default
    private long ttl = 86400L;

    /**
     * 内存缓存上限。
     * <p>格式如 "512MB"、"1GB"，为空表示不限制。</p>
     */
    private String memoryCacheLimit;

    /**
     * 是否预加载到内存。
     */
    @Builder.Default
    private boolean preloadToMemory = false;

    /**
     * 闪图 token 过期时间（秒）。
     * <p>默认 600（10 分钟）。</p>
     */
    @Builder.Default
    private long flashExpireSeconds = 600L;

    /**
     * 闪图使用后是否自动删除原文件。
     * <p>默认 false（只删除闪图本身，不删除原始上传文件）。</p>
     */
    @Builder.Default
    private boolean flashAutoDeleteOrigin = false;
}
