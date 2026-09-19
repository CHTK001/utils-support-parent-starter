package com.chua.filestorage.support.cache;

import com.chua.common.support.utils.DigestUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预览 PDF 本地磁盘缓存（增强版）。
 *
 * <p>将非原生预览文件（如 Word、Excel 等）转换为 PDF 后缓存到本地临时目录，
 * 避免重复转换。</p>
 *
 * <h3>增强特性</h3>
 * <ul>
 *   <li><b>TTL 自动过期</b> — 缓存文件超过 TTL 后自动删除，磁盘不会无限增长</li>
 *   <li><b>内存 LRU</b> — 热门文件缓存在内存中，避免反复磁盘 IO</li>
 *   <li><b>并发去重</b> — 同一文件并发请求只触发一次转换，通过 CompletableFuture 共享结果</li>
 *   <li><b>大小限制</b> — 超过阈值的文件不缓存到内存，防止 OOM</li>
 *   <li><b>安全清理</b> — 临时文件 try-finally 保证清理，后台定时清理过期文件</li>
 * </ul>
 *
 * <p>缓存键规则：{@code storageName}:{fileKey}</p>
 * <p>缓存文件命名：{@code <md5(key)>.pdf}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PreviewPdfCache {

    /** 默认缓存根目录（位于系统临时目录下） */
    private static final Path DEFAULT_CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "file-storage-preview-cache");

    /** 默认 TTL：1 天（秒） */
    private static final long DEFAULT_TTL_SECONDS = 86400L;

    /** 默认内存 LRU 容量（条目数） */
    private static final int DEFAULT_MEMORY_CAPACITY = 128;

    /** 默认单文件内存缓存上限：5MB */
    private static final long DEFAULT_MAX_MEMORY_FILE_SIZE = 5L * 1024 * 1024;

    /** 缓存目录 */
    private final Path cacheDir;

    /** 缓存 TTL（秒），0 表示永不过期 */
    private final long ttlSeconds;

    /** 内存 LRU 缓存：键 → pdf 字节（access-订单 驱逐最久未访问的） */
    private final LinkedHashMap<String, byte[]> memoryCache;

    /** 内存缓存容量（条目数） */
    private final int memoryCapacity;

    /** 单文件内存缓存上限（字节），超过此值不放入内存 */
    private final long maxMemoryFileSize;

    /** 并发去重：键 → 正在进行的转换 期货，避免同一文件重复转换 */
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inflightMap = new ConcurrentHashMap<>();

    /** 后台清理调度器 */
    private final ScheduledExecutorService cleanupScheduler;

    /** 独立转换线程池，隔离 PDF 转换任务，避免占用公共 ForkJoin 池 */
    private final java.util.concurrent.ExecutorService convertExecutor;

    /** 缓存统计：命中次数 */
    private final AtomicLong hitCount = new AtomicLong(0);

    /** 缓存统计：未命中次数 */
    private final AtomicLong missCount = new AtomicLong(0);

    /** 缓存统计：淘汰次数 */
    private final AtomicLong evictionCount = new AtomicLong(0);

    // ==================== 构造 ====================

    /** 创建 previewpdf缓存 实例（使用全部默认值） */
    public PreviewPdfCache() {
        this(DEFAULT_CACHE_DIR, DEFAULT_TTL_SECONDS, DEFAULT_MEMORY_CAPACITY, DEFAULT_MAX_MEMORY_FILE_SIZE);
    }

    /**
     * 创建 previewpdf缓存 实例
     * @param cacheDir 缓存目录
     */
    public PreviewPdfCache(Path cacheDir) {
        this(cacheDir, DEFAULT_TTL_SECONDS, DEFAULT_MEMORY_CAPACITY, DEFAULT_MAX_MEMORY_FILE_SIZE);
    }

    /**
     * 创建 previewpdf缓存 实例
     * @param cacheDir   缓存目录
     * @param ttlSeconds 缓存 TTL（秒），0 表示永不过期
     */
    public PreviewPdfCache(Path cacheDir, long ttlSeconds) {
        this(cacheDir, ttlSeconds, DEFAULT_MEMORY_CAPACITY, DEFAULT_MAX_MEMORY_FILE_SIZE);
    }

    /**
     * 创建 previewpdf缓存 实例（完整参数）
     *
     * @param cacheDir         缓存目录
     * @param ttlSeconds       缓存 TTL（秒），0 表示永不过期
     * @param memoryCapacity   内存 LRU 容量（条目数）
     * @param maxMemoryFileSize 单文件内存缓存上限（字节），超过此值不放入内存
     */
    public PreviewPdfCache(Path cacheDir, long ttlSeconds, int memoryCapacity, long maxMemoryFileSize) {
        this.cacheDir = cacheDir;
        this.ttlSeconds = ttlSeconds;
        this.memoryCapacity = memoryCapacity;
        this.maxMemoryFileSize = maxMemoryFileSize;

 // access-订单 链接哈希映射 实现 LRU
        this.memoryCache = new LinkedHashMap<>(memoryCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                boolean shouldRemove = size() > PreviewPdfCache.this.memoryCapacity;
                if (shouldRemove) {
                    PreviewPdfCache.this.evictionCount.incrementAndGet();
                    log.debug("内存 LRU 淘汰: key={}, size={}", eldest.getKey(), eldest.getValue().length);
                }
                return shouldRemove;
            }
        };

        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize preview cache dir: " + cacheDir, e);
        }

        // 后台定时清理过期文件（每小时执行一次）
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "preview-pdf-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        if (this.ttlSeconds > 0) {
            this.cleanupScheduler.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.HOURS);
        }

        // 独立转换线程池：单线程串行执行转换，隔离于公共池，避免阻塞核心业务线程
        this.convertExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "preview-pdf-convert");
            t.setDaemon(true);
            return t;
        });

        log.info("PreviewPdfCache 初始化: dir={}, ttl={}s, memoryCapacity={}, maxMemoryFileSize={}",
                cacheDir, ttlSeconds, memoryCapacity, maxMemoryFileSize);
    }

    // ==================== 核心 API ====================

    /**
     * 获取缓存的 PDF 字节（优先内存，其次磁盘）。
     *
     * <p>返回的字节数组是内存缓存的引用（不可修改），或从磁盘读取的新副本。</p>
     *
     * @param storageName 存储名称
     * @param key         文件 键
     * @return PDF 字节数组；若不存在或已过期返回 空
     */
    public byte[] get(String storageName, String key) {
        String cacheKey = buildCacheKey(storageName, key);

        // 1. 内存 LRU 命中
        synchronized (memoryCache) {
            byte[] cached = memoryCache.get(cacheKey);
            if (cached != null) {
                hitCount.incrementAndGet();
                log.debug("内存缓存命中: key={}, size={}", key, cached.length);
                // 返回副本，防止调用方修改共享的内存缓存数组
                return cached.clone();
            }
        }

        // 2. 磁盘缓存命中
        Path diskFile = getDiskCacheFile(cacheKey);
        if (diskFile != null) {
            try {
                byte[] diskBytes = Files.readAllBytes(diskFile);
                // 回填内存缓存（仅小文件）
                if (diskBytes.length <= maxMemoryFileSize) {
                    synchronized (memoryCache) {
                        memoryCache.put(cacheKey, diskBytes);
                    }
                }
                hitCount.incrementAndGet();
                log.debug("磁盘缓存命中: key={}, size={}", key, diskBytes.length);
                return diskBytes;
            } catch (IOException e) {
                log.warn("读取磁盘缓存失败: {}, 视为未命中", diskFile, e);
            }
        }

        missCount.incrementAndGet();
        return null;
    }

    /**
     * 获取缓存文件路径（兼容旧 API，内部委托 获取()）。
     *
     * <p>注意：此方法返回的 Path 仅在调用时有效，并发场景下建议使用 {@link #get(String, String)}。</p>
     *
     * @param storageName 存储名称
     * @param key         文件 键
     * @return 缓存文件路径，若不存在返回 空
     */
    public Path getCacheFile(String storageName, String key) {
        String cacheKey = buildCacheKey(storageName, key);
        return getDiskCacheFile(cacheKey);
    }

    /**
     * 写入缓存（同时写磁盘和内存）。
     *
     * @param storageName 存储名称
     * @param key         文件 键
     * @param pdfBytes    转换后的 PDF 字节数组
     * @return 写入后的磁盘文件路径
     * @throws IOException IO 异常
     */
    public Path writeCache(String storageName, String key, byte[] pdfBytes) throws IOException {
        String cacheKey = buildCacheKey(storageName, key);
        String fileName = DigestUtils.md5(cacheKey) + ".pdf";
        Path target = cacheDir.resolve(fileName);

        // 写磁盘（原子写：先写临时文件再 rename）
        Path tempFile = cacheDir.resolve(".tmp_" + DigestUtils.md5(cacheKey) + ".pdf");
        try {
            Files.write(tempFile, pdfBytes);
            Files.move(tempFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        // 回填内存缓存（仅小文件）
        if (pdfBytes.length <= maxMemoryFileSize) {
            synchronized (memoryCache) {
                memoryCache.put(cacheKey, pdfBytes);
            }
        }

        log.debug("缓存写入完成: key={}, size={}, disk={}", key, pdfBytes.length, target);
        return target;
    }

    /**
     * 带并发去重的缓存获取/转换。
     *
     * <p>如果同一文件正在转换中，后续请求会等待第一个转换完成并共享结果，
     * 避免重复转换浪费 CPU/IO。</p>
     *
     * @param storageName 存储名称
     * @param key         文件 键
     * @param converter   转换函数（仅在缓存未命中且无进行中转换时调用）
     * @return PDF 字节数组；转换失败返回 空
     */
    public byte[] getOrConvert(String storageName, String key, java.util.function.Supplier<byte[]> converter) {
        // 1. 先查缓存（内存 + 磁盘）
        byte[] cached = get(storageName, key);
        if (cached != null) {
            return cached;
        }

 // 2. 并发去重：用 completable期货 确保同一 键 只触发一次转换
        String cacheKey = buildCacheKey(storageName, key);
        CompletableFuture<byte[]> future = inflightMap.computeIfAbsent(cacheKey, k -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] result = converter.get();
                    if (result != null) {
                        // 写入缓存（磁盘 + 内存）
                        writeCache(storageName, key, result);
                    }
                    return result;
                } catch (Exception e) {
                    log.warn("PDF 转换失败: key={}, error={}", key, e.getMessage());
                    return null;
                } finally {
                    inflightMap.remove(k);
                }
            }, convertExecutor);
        });

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("等待 PDF 转换完成超时或失败: key={}, error={}", key, e.getMessage());
            // 取消进行中的转换，避免资源泄漏
            future.cancel(true);
            inflightMap.remove(cacheKey);
            return null;
        }
    }

    // ==================== 清理 ====================

    /**
     * 清理指定 键 的缓存（磁盘 + 内存）。
     *
     * @param storageName 存储名称
     * @param key         文件 键
     */
    public void evict(String storageName, String key) {
        String cacheKey = buildCacheKey(storageName, key);

        // 清内存
        synchronized (memoryCache) {
            memoryCache.remove(cacheKey);
        }

        // 清磁盘
        try {
            String fileName = DigestUtils.md5(cacheKey) + ".pdf";
            Path target = cacheDir.resolve(fileName);
            Files.deleteIfExists(target);
            log.debug("缓存已清理: key={}", key);
        } catch (IOException e) {
            log.warn("清理磁盘缓存失败: key={}", key, e);
        }
    }

    /**
     * 清空全部缓存（磁盘 + 内存）。
     */
    public void clearAll() {
        // 清内存
        synchronized (memoryCache) {
            memoryCache.clear();
        }

        // 清磁盘
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

    // ==================== 统计 ====================

    /**
     * 获取缓存统计信息
     *
     * @return 获取stats的结果
     */
    public CacheStats getStats() {
        synchronized (memoryCache) {
            return new CacheStats(hitCount.get(), missCount.get(), evictionCount.get(), memoryCache.size());
        }
    }

    /**
     * 缓存统计信息
     *
     * @param hitCount hit数量
     * @param missCount miss数量
     * @param evictionCount eviction数量
     * @param memorySize 内存大小
     * @return 缓存stats的结果
     */
    public record CacheStats(long hitCount, long missCount, long evictionCount, int memorySize) {
        /**
         * 命中率
         *
         * @return hitRate的结果
         */
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{hit=%d, miss=%d, rate=%.1f%%, evictions=%d, memory=%d}",
                    hitCount, missCount, hitRate() * 100, evictionCount, memorySize);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 检查磁盘缓存文件是否存在且未过期。
     *
     * @param cacheKey 缓存键
     * @return 缓存文件路径；不存在或已过期返回 空（过期文件会被自动删除）
     */
    private Path getDiskCacheFile(String cacheKey) {
        String fileName = DigestUtils.md5(cacheKey) + ".pdf";
        Path target = cacheDir.resolve(fileName);

        if (!Files.exists(target)) {
            return null;
        }

        // TTL 过期检查
        if (ttlSeconds > 0) {
            try {
                FileTime lastModified = Files.getLastModifiedTime(target);
                long ageSeconds = TimeUnit.MILLISECONDS.toSeconds(
                        System.currentTimeMillis() - lastModified.toMillis());
                if (ageSeconds > ttlSeconds) {
                    Files.deleteIfExists(target);
                    // 同步清理内存缓存
                    synchronized (memoryCache) {
                        memoryCache.remove(cacheKey);
                    }
                    log.debug("缓存已过期并删除: key={}, age={}s, ttl={}s", cacheKey, ageSeconds, ttlSeconds);
                    return null;
                }
            } catch (IOException e) {
                log.warn("检查缓存 TTL 失败: {}, 视为未命中", target, e);
                return null;
            }
        }

        return target;
    }

    /**
     * 后台清理过期缓存文件。
     * <p>由 ScheduledExecutorService 每小时调用一次，扫描磁盘目录删除过期文件。</p>
     */
    private void cleanupExpired() {
        if (ttlSeconds <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long ttlMillis = TimeUnit.SECONDS.toMillis(ttlSeconds);
        int deleted = 0;

        try (var stream = Files.list(cacheDir)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!file.toString().endsWith(".pdf")) {
                    continue;
                }
                try {
                    FileTime lastModified = Files.getLastModifiedTime(file);
                    if (now - lastModified.toMillis() > ttlMillis) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("清理过期缓存文件失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描缓存目录失败: {}", cacheDir, e);
        }

        if (deleted > 0) {
            log.info("后台清理过期缓存: 删除 {} 个文件", deleted);
        }
    }

    /**
     * 构建缓存键。
     *
     * @param storageName 存储名称
     * @param key         文件 键
     * @return 缓存键
     */
    private String buildCacheKey(String storageName, String key) {
        return storageName + ":" + key + ":pdf";
    }

    /**
     * 关闭缓存（停止后台清理线程）。
     */
    public void close() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        convertExecutor.shutdownNow();
        log.info("PreviewPdfCache 已关闭: {}", getStats());
    }
}
