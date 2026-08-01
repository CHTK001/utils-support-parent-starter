package com.chua.filesearch.support.service;

import com.chua.filesystem.support.filesearch.model.FileInfo;
import com.chua.filesystem.support.filesearch.model.FileSearchCriteria;
import com.chua.filesystem.support.filesearch.spi.FileSearchProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.filesearch.support.spi.impl.NativeFileSearchProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 文件搜索服务门面
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public final class FileSearchService {

    private static volatile FileSearchService INSTANCE;
    private final FileSearchProvider provider;

    private FileSearchService() {
        FileSearchProvider p = null;

        try {
            ServiceProvider<FileSearchProvider> providerService = ServiceProvider.of(FileSearchProvider.class);
            p = providerService.getDefault();
            if (p != null) {
                log.info("FileSearchProvider loaded via SPI: {}", p.getClass().getName());
            }
        } catch (Exception e) {
            log.trace("SPI discovery skipped: {}", e.getMessage());
        }

        if (p == null) {
            try {
                p = new com.chua.filesearch.support.spi.impl.NativeFileSearchProvider();
                log.info("FileSearchProvider created via fallback: {}", p.getClass().getName());
            } catch (Exception e) {
                log.warn("Failed to create NativeFileSearchProvider: {}", e.getMessage());
            }
        }

        this.provider = p;
    }

    /**
     * 获取全局单例
     *
     * @return 服务实例
     */
    public static FileSearchService getInstance() {
        if (INSTANCE == null) {
            synchronized (FileSearchService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FileSearchService();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 服务是否可用
     *
     * @return true 表示可用
     */
    public boolean isAvailable() {
        return provider != null;
    }

    /**
     * 搜索文件
     *
     * @param criteria 搜索条件
     * @return 文件列表
     */
    public List<FileInfo> search(FileSearchCriteria criteria) {
        if (!isAvailable()) {
            log.warn("FileSearchService not available");
            return Collections.emptyList();
        }
        try {
            return provider.searchFiles(criteria);
        } catch (Exception e) {
            log.error("Error searching files: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 按名称搜索文件
     *
     * @param rootPath 根目录
     * @param pattern  通配符模式
     * @return 文件列表
     */
    public List<FileInfo> searchByName(String rootPath, String pattern) {
        return search(FileSearchCriteria.builder()
                .rootPath(rootPath)
                .namePattern(pattern)
                .build());
    }

    /**
     * 按大小搜索文件
     *
     * @param rootPath 根目录
     * @param minSize  最小大小（字节）
     * @param maxSize  最大大小（字节）
     * @return 文件列表
     */
    public List<FileInfo> searchBySize(String rootPath, long minSize, long maxSize) {
        return search(FileSearchCriteria.builder()
                .rootPath(rootPath)
                .minSize(minSize)
                .maxSize(maxSize)
                .build());
    }

    /**
     * 获取目录树
     *
     * @param rootPath 根目录
     * @return 目录树文件列表
     */
    public List<FileInfo> getTree(String rootPath) {
        return search(FileSearchCriteria.builder()
                .rootPath(rootPath)
                .build());
    }
}
