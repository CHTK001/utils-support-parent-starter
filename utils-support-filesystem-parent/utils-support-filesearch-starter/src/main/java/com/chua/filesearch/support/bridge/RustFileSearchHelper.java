package com.chua.filesearch.support.bridge;

import com.chua.filesystem.support.filesearch.model.FileInfo;
import com.chua.filesystem.support.filesearch.model.FileSearchCriteria;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;



/**
 * Rust 原生文件搜索执行器
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class RustFileSearchHelper {

    /**
     * 流式搜索批量缓冲区大小
     */
    private static final int STREAM_BATCH_SIZE = 128;
    /**
     * 大小不限标记值
     */
    private static final long SIZE_UNLIMITED = -1;
    /**
     * 文件占比未计算时的默认值
     */
    private static final double PERCENTAGE_NOT_CALCULATED = 0.0;

    static {
        RustFileSearchBridge.loadLibrary();
    }

    /**
     * 同步搜索文件
     *
     * @param criteria 搜索条件
     * @return 文件列表
     */
    public static List<FileInfo> search(FileSearchCriteria criteria) {
        List<FileInfo> results = search0(criteria);
        if (!results.isEmpty()) {
            return results;
        }
        log.warn("Native search returned empty results, falling back to JDK walkdir");
        return searchByJdk(criteria);
    }

    private static List<FileInfo> search0(FileSearchCriteria criteria) {
        if (!RustFileSearchBridge.isLoaded()) {
            return new ArrayList<>();
        }

        String rootPath = resolveRootPath(criteria.rootPath());
        List<FileInfo> results = new ArrayList<>();

        int errorCode;
        if (criteria.namePattern() != null && !criteria.namePattern().isBlank()) {
            errorCode = RustFileSearchBridge.searchByName(
                    rootPath, criteria.namePattern(), criteria.maxResults(),
                    data -> results.add(buildFileInfo(data))
            );
        } else if (criteria.minSize() > 0 || criteria.maxSize() > 0) {
            long min = criteria.minSize() > 0 ? criteria.minSize() : 0;
            long max = criteria.maxSize() > 0 ? criteria.maxSize() : SIZE_UNLIMITED;
            errorCode = RustFileSearchBridge.searchBySize(
                    rootPath, min, max, criteria.maxResults(),
                    data -> results.add(buildFileInfo(data))
            );
        } else {
            errorCode = RustFileSearchBridge.getTree(
                    rootPath, criteria.maxDepth(), criteria.maxResults(),
                    data -> results.add(buildFileInfo(data))
            );
        }

        if (errorCode < 0) {
            log.warn("Native search returned error code: {}", errorCode);
        }

        if (results.size() > 1) {
            postProcessTree(results);
        }
        return results;
    }

    private static List<FileInfo> searchByJdk(FileSearchCriteria criteria) {
        String rootPath = resolveRootPath(criteria.rootPath());
        Path root = Paths.get(rootPath);
        if (Files.notExists(root) || !Files.isDirectory(root)) {
            return new ArrayList<>();
        }

        List<FileInfo> results = new ArrayList<>();
        int maxResults = criteria.maxResults() > 0 ? criteria.maxResults() : Integer.MAX_VALUE;
        String namePattern = criteria.namePattern();

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> namePattern == null || namePattern.isBlank()
                            || matchesGlob(p.getFileName().toString(), namePattern))
                    .limit(maxResults)
                    .forEach(p -> {
                        try {
                            long size = Files.size(p);
                            results.add(new FileInfo(
                                    p.getFileName().toString(),
                                    p.toAbsolutePath().toString(),
                                    size,
                                    formatSize(size),
                                    p.getParent() != null ? p.getParent().toString() : "",
                                    PERCENTAGE_NOT_CALCULATED,
                                    Files.getLastModifiedTime(p).toMillis(),
                                    false,
                                    extensionOf(p),
                                    0, 0L, 0L, 0L, 0L, 0L
                            ));
                        } catch (IOException e) {
                            log.debug("skip {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("JDK walkdir failed: {}", e.getMessage());
        }

        return results;
    }

    private static boolean matchesGlob(String name, String pattern) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) {
            return true;
        }
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return name.matches(regex);
    }

    private static String extensionOf(Path p) {
        String name = p.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx >= 0 && idx < name.length() - 1) {
            return name.substring(idx + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 流式搜索文件
     *
     * @param criteria  搜索条件
     * @param consumer  结果消费者
     * @return 实际匹配数
     */
    public static long searchStream(FileSearchCriteria criteria, Consumer<FileInfo> consumer) {
        if (!RustFileSearchBridge.isLoaded()) {
            log.warn("Native file search library not loaded");
            return 0;
        }

        String rootPath = resolveRootPath(criteria.rootPath());
        List<FileInfo> buffer = new ArrayList<>(STREAM_BATCH_SIZE);
        int batchSize = STREAM_BATCH_SIZE;

        int errorCode;
        if (criteria.namePattern() != null && !criteria.namePattern().isBlank()) {
            errorCode = RustFileSearchBridge.searchByName(
                    rootPath, criteria.namePattern(), criteria.maxResults(),
                    data -> {
                        buffer.add(buildFileInfo(data));
                        if (buffer.size() >= batchSize) {
                            buffer.forEach(consumer);
                            buffer.clear();
                        }
                    }
            );
        } else if (criteria.minSize() > 0 || criteria.maxSize() > 0) {
            long min = criteria.minSize() > 0 ? criteria.minSize() : 0;
            long max = criteria.maxSize() > 0 ? criteria.maxSize() : SIZE_UNLIMITED;
            errorCode = RustFileSearchBridge.searchBySize(
                    rootPath, min, max, criteria.maxResults(),
                    data -> {
                        buffer.add(buildFileInfo(data));
                        if (buffer.size() >= batchSize) {
                            buffer.forEach(consumer);
                            buffer.clear();
                        }
                    }
            );
        } else {
            errorCode = RustFileSearchBridge.getTree(
                    rootPath, criteria.maxDepth(), criteria.maxResults(),
                    data -> {
                        buffer.add(buildFileInfo(data));
                        if (buffer.size() >= batchSize) {
                            buffer.forEach(consumer);
                            buffer.clear();
                        }
                    }
            );
        }

        buffer.forEach(consumer);
        return Math.max(0, errorCode);
    }

    /**
     * 取消正在进行的搜索
     */
    public static void cancel() {
        RustFileSearchBridge.cancel();
    }

    /**
     * 获取引擎版本
     *
     * @return 版本字符串
     */
    public static String getVersion() {
        return RustFileSearchBridge.getVersion();
    }

    private static void postProcessTree(List<FileInfo> results) {
        Map<String, List<FileInfo>> parentToChildren = new HashMap<>();
        Map<String, FileInfo> dirIndex = new HashMap<>();

        for (FileInfo fi : results) {
            if (fi.isDirectory()) {
                dirIndex.put(fi.path(), fi);
            }
            parentToChildren.computeIfAbsent(fi.parentDir(), k -> new ArrayList<>()).add(fi);
        }

        Map<String, long[]> dirTotals = new HashMap<>();
        for (FileInfo fi : results) {
            if (fi.isDirectory()) {
                computeDirTotals(fi.path(), parentToChildren, dirTotals);
            }
        }

        Map<String, Long> parentTotalSize = new HashMap<>();
        for (Map.Entry<String, List<FileInfo>> e : parentToChildren.entrySet()) {
            long total = 0;
            for (FileInfo fi : e.getValue()) {
                if (fi.isDirectory()) {
                    long[] t = dirTotals.get(fi.path());
                    total += t != null ? t[0] : 0;
                } else {
                    total += fi.size();
                }
            }
            parentTotalSize.put(e.getKey(), total);
        }

        List<FileInfo> enriched = new ArrayList<>(results.size());
        for (FileInfo fi : results) {
            long fileCount = 0, dirCount = 0, dirSize = 0;
            if (fi.isDirectory()) {
                long[] t = dirTotals.get(fi.path());
                if (t != null) {
                    dirSize = t[0];
                    fileCount = t[1];
                    dirCount = t[2];
                }
            }

            double percentage = PERCENTAGE_NOT_CALCULATED;
            long displaySize = fi.isDirectory() ? dirSize : fi.size();
            Long parentTotal = parentTotalSize.get(fi.parentDir());
            if (parentTotal != null && parentTotal > 0) {
                percentage = Math.round((double) displaySize / parentTotal * 10000.0) / 100.0;
            }

            enriched.add(new FileInfo(
                    fi.name(), fi.path(), fi.isDirectory() ? dirSize : fi.size(),
                    formatSize(fi.isDirectory() ? dirSize : fi.size()),
                    fi.parentDir(), percentage, fi.lastModified(),
                    fi.isDirectory(), fi.extension(), fi.attributes(),
                    fi.usnRecordId(), fi.parentFileId(),
                    fileCount, dirCount,
                    fi.allocatedSize()
            ));
        }
        results.clear();
        results.addAll(enriched);
    }

    private static long[] computeDirTotals(String dirPath, Map<String, List<FileInfo>> children, Map<String, long[]> cache) {
        long[] cached = cache.get(dirPath);
        if (cached != null) {
            return cached;
        }

        long totalSize = 0, totalFiles = 0, totalDirs = 0;
        List<FileInfo> entries = children.get(dirPath);
        if (entries != null) {
            for (FileInfo fi : entries) {
                if (fi.isDirectory()) {
                    totalDirs++;
                    long[] sub = computeDirTotals(fi.path(), children, cache);
                    totalSize += sub[0];
                    totalFiles += sub[1];
                    totalDirs += sub[2];
                } else {
                    totalFiles++;
                    totalSize += fi.size();
                }
            }
        }

        long[] result = new long[]{totalSize, totalFiles, totalDirs};
        cache.put(dirPath, result);
        return result;
    }

    private static String resolveRootPath(String rootPath) {
        if (rootPath != null && !rootPath.isBlank()) {
            return rootPath;
        }
        return System.getProperty("user.dir");
    }

    private static FileInfo buildFileInfo(RustFileSearchBridge.FileResultData data) {
        Path p = Paths.get(data.path());
        String name = p.getFileName() != null ? p.getFileName().toString() : data.path();
        String parentDir = "";
        int lastSep = Math.max(data.path().lastIndexOf('/'), data.path().lastIndexOf('\\'));
        if (lastSep > 0) {
            parentDir = data.path().substring(0, lastSep);
        }

        return new FileInfo(
                name,
                data.path(),
                data.size(),
                formatSize(data.size()),
                parentDir,
                PERCENTAGE_NOT_CALCULATED,
                data.lastModified(),
                data.isDirectory(),
                data.extension(),
                data.attributes(),
                data.usnRecordId(),
                data.parentFileId(),
                0,
                0,
                data.allocatedSize()
        );
    }

    static final class AttributeNames {
        /** Masks */
        private static final int[] MASKS = {
                0x0001, 0x0002, 0x0004, 0x0010, 0x0020, 0x0080,
                0x0100, 0x0200, 0x0400, 0x0800, 0x1000, 0x2000, 0x4000
        };
        /** Names */
        private static final String[] NAMES = {
                "R", "H", "S", "D", "A", "N",
                "T", "SP", "RP", "C", "O", "I", "E"
        };

        static String attributeString(int attrs, boolean isDirectory) {
            if (attrs == 0 && !isDirectory) {
                return "0x00000000";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MASKS.length; i++) {
                if ((attrs & MASKS[i]) != 0) {
                    sb.append(NAMES[i]);
                }
            }
            if (isDirectory && sb.indexOf("D") == -1) {
                sb.insert(0, 'D');
            }
            return sb.length() > 0 ? sb.toString() : String.format("0x%08X", attrs);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}
