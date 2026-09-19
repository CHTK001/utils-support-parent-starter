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
    /**
     * PDF缓存目录
    */
    private String pdfCacheDir = System.getProperty("java.io.tmpdir") + "/filestorage-pdf-cache";

    /**
     * 闪图 0 字节 记号笔 文件目录。
     * <p>默认为 {@code ${java.io.tmpdir}/filestorage-flash}</p>
     */
    @Builder.Default
    /**
     * Flash目录
    */
    private String flashDir = System.getProperty("java.io.tmpdir") + "/filestorage-flash";

    /**
     * 缓存 TTL（秒）。
     * <p>默认 86400（1 天）。0 表示永不过期。</p>
     */
    @Builder.Default
    /**
     * TTL
    */
    private long ttl = 86400L;

    /**
     * 内存 LRU 缓存容量（条目数）。
     * <p>默认 128。0 表示不启用内存缓存。</p>
     */
    @Builder.Default
    /**
     * 内存缓存容量
    */
    private int memoryCacheCapacity = 128;

    /**
     * 单文件内存缓存上限（字节）。
     * <p>超过此大小的 PDF 不会放入内存缓存，默认 5MB。</p>
     */
    @Builder.Default
    /**
     * 单文件内存上限
    */
    private long maxMemoryFileSize = 5L * 1024 * 1024;

    /**
     * 闪图 令牌 过期时间（秒）。
     * <p>默认 600（10 分钟）。</p>
     */
    @Builder.Default
    /**
     * Flashexpire秒
    */
    private long flashExpireSeconds = 600L;

    /**
     * 闪图使用后是否自动删除原文件。
     * <p>默认 false（只删除闪图本身，不删除原始上传文件）。</p>
     */
    @Builder.Default
    /**
     * Flashautodeleteorigin
    */
    private boolean flashAutoDeleteOrigin = false;
}
