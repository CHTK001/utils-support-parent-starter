package com.chua.filestorage.support.cache;

import com.chua.common.support.utils.DigestUtils;
import com.chua.filestorage.support.setting.FileStorageSetting;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 预览 PDF 本地磁盘缓存。
 *
 * <p>将非原生预览文件（如 Word、Excel 等）转换为 PDF 后缓存到本地临时目录，
 * 避免重复转换。</p>
 *
 * <p>缓存键规则：{@code storageName}:{fileKey}</p>
 * <p>缓存文件命名：{@code <sha256(key + ".pdf")>.pdf}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PreviewPdfCache {

    /**
     * 默认缓存根目录（位于系统临时目录下）。
     */
    private static final Path DEFAULT_CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "file-storage-preview-cache");

    /** 缓存目录 */
    private final Path cacheDir;

    public PreviewPdfCache() {
        this(DEFAULT_CACHE_DIR);
    }

    public PreviewPdfCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize preview cache dir: " + cacheDir, e);
        }
    }

    /**
     * 根据存储名称和文件 Key 获取缓存文件路径。
     *
     * @param storageName 存储名称
     * @param key         文件 Key
     * @return 缓存文件路径，若不存在返回 null
     */
    public Path getCacheFile(String storageName, String key) {
        String cacheKey = buildCacheKey(storageName, key);
        String fileName = DigestUtils.md5(cacheKey) + ".pdf";
        Path target = cacheDir.resolve(fileName);
        return Files.exists(target) ? target : null;
    }

    /**
     * 将转换后的 PDF 字节写入缓存。
     *
     * @param storageName 存储名称
     * @param key         文件 Key
     * @param pdfBytes    转换后的 PDF 字节数组
     * @return 写入后的缓存文件路径
     * @throws IOException IO 异常
     */
    public Path writeCache(String storageName, String key, byte[] pdfBytes) throws IOException {
        String cacheKey = buildCacheKey(storageName, key);
        String fileName = DigestUtils.md5(cacheKey) + ".pdf";
        Path target = cacheDir.resolve(fileName);
        Files.write(target, pdfBytes);
        log.debug("预览 PDF 缓存写入完成: storage={}, key={}, path={}", storageName, key, target);
        return target;
    }

    /**
     * 清理指定 Key 的缓存文件。
     *
     * @param storageName 存储名称
     * @param key         文件 Key
     */
    public void evict(String storageName, String key) {
        try {
            String cacheKey = buildCacheKey(storageName, key);
            String fileName = DigestUtils.md5(cacheKey) + ".pdf";
            Path target = cacheDir.resolve(fileName);
            Files.deleteIfExists(target);
            log.debug("预览 PDF 缓存已清理: storage={}, key={}", storageName, key);
        } catch (IOException e) {
            log.warn("清理预览缓存失败: storage={}, key={}", storageName, key, e);
        }
    }

    /**
     * 清空全部缓存。
     */
    public void clearAll() {
        try {
            Files.list(cacheDir)
                    .filter(p -> p.toString().endsWith(".pdf"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除缓存文件失败: {}", p, e);
                        }
                    });
            log.info("预览 PDF 缓存已清空");
        } catch (IOException e) {
            log.warn("清空预览缓存失败", e);
        }
    }

    private String buildCacheKey(String storageName, String key) {
        return storageName + ":" + key + ":pdf";
    }
}
