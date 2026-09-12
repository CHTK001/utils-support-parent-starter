package com.chua.common.support.network.download.extractor;

import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
* 解压文件工厂类。
* <p>
* 基于 SPI 机制动态加载不同的解压实现，提供线程安全的文件解压功能。
*
* @author CH
* @version 1.0.0
* @since 2025/11/29
 */
@Slf4j
public class ExtractorFactory {

    /**
    * 用于管理并发解压操作的锁映射表。
    * Key: 压缩文件的绝对路径。
    * Value: 对应的重入锁。
     */
    private static final Map<String, ReentrantLock> EXTRACTION_LOCKS = new ConcurrentHashMap<>();

    /**
    * 私有构造函数，防止外部实例化。
     */
    private ExtractorFactory() {
    }

    /**
    * 根据文件名获取支持的解压器。
    *
    * @param fileName 文件名
    * @return 匹配的解压器，如果未找到则返回 null
     */
    public static Extractor getExtractor(String fileName) {
        if (fileName == null) {
            return null;
        }

        List<Extractor> extractors = ServiceProvider.of(Extractor.class).collect();
        for (Extractor extractor : extractors) {
            if (extractor.supports(fileName)) {
                return extractor;
            }
        }

        return null;
    }

    /**
    * 检查指定文件名是否支持解压。
    *
    * @param fileName 文件名
    * @return 如果支持解压返回 true，否则返回 false
     */
    public static boolean isSupported(String fileName) {
        return getExtractor(fileName) != null;
    }

    /**
    * 执行文件解压操作。
    * <p>
    * 该方法是线程安全的，会对同一压缩文件进行加锁处理，防止重复解压。
    * 如果目标目录已存在且包含有效内容，则直接返回；否则执行解压逻辑。
    *
    * @param compressedFile 待解压的压缩文件
    * @param extractionDir  解压后的目标目录
    * @param keepOriginal   是否保留原始压缩文件
    * @param fileName       文件名（用于判断解压器类型）
    * @return 解压后的目录，如果失败或参数无效则返回 null
     */
    public static File extract(File compressedFile, File extractionDir, boolean keepOriginal, String fileName) {
        if (compressedFile == null || !compressedFile.exists()) {
            log.warn("压缩文件不存在或为空：{}", compressedFile);
            return null;
        }

        Extractor extractor = getExtractor(fileName);
        if (extractor == null) {
            if (log.isDebugEnabled()) {
                log.debug("不支持的文件格式：{}", fileName);
            }
            // 如果不支持解压，直接返回原文件
            return compressedFile;
        }

        // 计算解压后的基础目录名
        String baseName = extractor.getBaseName(compressedFile.getName());
        File targetDir = new File(extractionDir, baseName);

        // 检查目标目录是否已存在且包含有效内容
        if (targetDir.exists() && targetDir.isDirectory() && hasValidContent(targetDir)) {
            log.info("文件已解压，跳过：{} -> {}", compressedFile.getName(), targetDir.getAbsolutePath());
            return targetDir;
        }

        // 构建锁键值并获取锁对象
        String lockKey = compressedFile.getAbsolutePath();
        ReentrantLock lock = EXTRACTION_LOCKS.computeIfAbsent(lockKey, k -> new ReentrantLock());

        lock.lock();
        try {
            // 双重检查：在持有锁的情况下再次确认目录状态
            if (targetDir.exists() && targetDir.isDirectory() && hasValidContent(targetDir)) {
                if (log.isDebugEnabled()) {
                    log.debug("竞争条件下发现目录已存在：{}", targetDir.getAbsolutePath());
                }
                return targetDir;
            }

            log.info("开始解压文件：{} -> {}", compressedFile.getName(), targetDir.getAbsolutePath());

            // 创建目标目录
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                log.error("创建目标目录失败：{}", targetDir.getAbsolutePath());
                return null;
            }

            boolean success = extractor.extract(compressedFile, targetDir);

            if (success) {
                log.info("解压成功：{} -> {}", compressedFile.getName(), targetDir.getAbsolutePath());

                // 如果不需要保留原始文件，则删除它
                if (!keepOriginal) {
                    try {
                        Files.delete(compressedFile.toPath());
                        if (log.isDebugEnabled()) {
                            log.debug("已删除原始压缩文件：{}", compressedFile.getAbsolutePath());
                        }
                    } catch (IOException e) {
                        log.warn("删除原始压缩文件失败：{}", compressedFile.getAbsolutePath(), e);
                    }
                }

                return targetDir;
            } else {
                log.error("解压失败：{}", compressedFile.getName());
                // 清理失败的解压目录
                deleteDirectory(targetDir);
                return null;
            }

        } finally {
            lock.unlock();
            EXTRACTION_LOCKS.remove(lockKey);
        }
    }

    /**
    * 检查目录是否存在且包含有效内容（非空）。
    *
    * @param dir 待检查的目录
    * @return 如果目录存在、是目录且包含文件则返回 true，否则返回 false
     */
    private static boolean hasValidContent(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    /**
    * 递归删除指定的目录及其所有子文件和子目录。
    *
    * @param dir 待删除的目录
     */
    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try {
            Files.walk(dir.toPath())
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除文件或目录失败：{}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("删除目录失败：{}", dir.getAbsolutePath(), e);
        }
    }
}
