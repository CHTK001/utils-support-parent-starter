package com.chua.filestorage.support.filter;

import com.chua.common.support.file.converter.ImageOperation;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.cache.PreviewPdfCache;
import com.chua.filestorage.support.flash.FlashTokenService;
import com.chua.filestorage.support.operation.FileOperationSetting;
import com.chua.filestorage.support.setting.FileStorageSetting;
import com.chua.filestorage.support.spi.FileStorageFileSetting;
import com.chua.filestorage.support.spi.FileStorageFilterSetting;
import com.chua.filestorage.support.utils.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static java.util.concurrent.ConcurrentHashMap.newKeySet;

/**
 * 文件存储服务器过滤器（抽象基类）。
 *
 * <p>聚合 FileStorage 实例，提供预览、下载、图片滤镜、闪图等功能。
 * 支持热重载（upgrade）更新 文件setting / 过滤器setting / 镜像operation。</p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Slf4j
public abstract class AbstractFileStorageServerFilter implements ServerFilter {

    /** 全局共享：所有实例的 pdf缓存 引用，用于 JVM 关闭时统一释放 */
    private static final Set<PreviewPdfCache> ALL_CACHES = newKeySet();
    /** 全局共享：单个 JVM 关闭 hook，避免多实例重复注册 */
    private static volatile Thread shutdownHook;

    /** storage映射 */
    protected final Map<String, FileStorage> storageMap = new ConcurrentHashMap<>();
    /** 设置 */
    protected final FileStorageSetting setting;

    /** PDF缓存 */
    protected PreviewPdfCache pdfCache;
    /** 文件设置 */
    protected FileStorageFileSetting fileSetting;
    /** 过滤器设置 */
    protected FileStorageFilterSetting filterSetting;
    /** 图片operation */
    protected ImageOperation imageOperation;
    /** flash服务 */
    protected transient FlashTokenService flashService;

    /**
    * 创建 抽象文件storage服务端过滤器 实例
    * @param setting setting
    */
    public AbstractFileStorageServerFilter(FileStorageSetting setting) {
        this(setting, null);
    }

    /**
     * 创建 抽象文件storage服务端过滤器 实例
     * @param setting setting
     * @param pdfCache previewpdf缓存
     * @param pdfCache pdf缓存
     */
    public AbstractFileStorageServerFilter(FileStorageSetting setting, PreviewPdfCache pdfCache) {
        this.setting = setting;
        if (pdfCache != null) {
            this.pdfCache = pdfCache;
        } else {
            var cacheSetting = setting.getCache();
            this.pdfCache = new PreviewPdfCache(
                    Path.of(cacheSetting.getPdfCacheDir()),
                    cacheSetting.getTtl(),
                    cacheSetting.getMemoryCacheCapacity(),
                    cacheSetting.getMaxMemoryFileSize());
        }
        loadSpis();
 // 注册全局共享 关闭 hook（仅首次创建时注册）
        registerShutdownHook();
 // 将此实例的 缓存 注册到全局集合
        ALL_CACHES.add(this.pdfCache);
    }

    /**
     * 注册全局共享的 JVM 关闭 hook，确保所有 previewpdf缓存 实例的后台清理线程被释放。
     * 使用 double-检查 锁 确保只注册一次。
     */
    private static void registerShutdownHook() {
        if (shutdownHook == null) {
            synchronized (AbstractFileStorageServerFilter.class) {
                if (shutdownHook == null) {
                    shutdownHook = new Thread(() -> {
                        for (PreviewPdfCache cache : ALL_CACHES) {
                            try {
                                cache.close();
                            } catch (Exception e) {
 // 关闭 期间忽略异常
                            }
                        }
                    }, "filestorage-cache-shutdown");
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                }
            }
        }
    }

    /** 加载Spis */
    private void loadSpis() {
        String fsKey = StringUtils.isEmpty(setting.getFileSettingKey()) ? "jdk" : setting.getFileSettingKey();
        this.fileSetting = ServiceProvider.of(FileStorageFileSetting.class).getNewExtension(fsKey);

        String filterKey = StringUtils.isEmpty(setting.getFilterSettingKey()) ? "jdk" : setting.getFilterSettingKey();
        this.filterSetting = ServiceProvider.of(FileStorageFilterSetting.class).getNewExtension(filterKey);

        String imgOpKey = StringUtils.isEmpty(setting.getImageFilterKey()) ? null : setting.getImageFilterKey();
        this.imageOperation = ServiceProvider.of(ImageOperation.class).getExtension(imgOpKey);
    }

    /**
    * 添加文件storage
    *
    * @param name 名称
    * @param storage storage
    */
    public void addFileStorage(String name, FileStorage storage) {
        storageMap.put(name, storage);
    }

    /**
     * 获取文件storage
     *
     * @param name 名称
     * @return 获取文件storage的结果
     */
    public FileStorage getFileStorage(String name) {
        FileStorage storage = storageMap.get(name);
        if (storage != null) {
            return storage;
        }
        if (!"default".equals(name)) {
            return storageMap.get("default");
        }
        return storageMap.isEmpty() ? null : storageMap.values().iterator().next();
    }

    /**
     * 获取Pdf缓存。
     *
     * @return PreviewPdf缓存 对象
     */
    PreviewPdfCache getPdfCache() {
        return pdfCache;
    }

    /**
     * 热重载配置（更新 文件setting 和 过滤器setting）。
     *
     * @param fileSetting 新的 文件storage文件setting 实例（传 空 保留原值）
     * @param filterSetting 新的 文件storage过滤器setting 实例（传 空 保留原值）
     */
    public void upgrade(FileStorageFileSetting fileSetting, FileStorageFilterSetting filterSetting) {
        if (fileSetting != null) {
            this.fileSetting = fileSetting;
        }
        if (filterSetting != null) {
            this.filterSetting = filterSetting;
        }
        log.info("[FileStorageFilter] 配置热重载: fileSetting={}, filterSetting={}",
                fileSetting != null ? fileSetting.getClass().getSimpleName() : "unchanged",
                filterSetting != null ? filterSetting.getClass().getSimpleName() : "unchanged");
    }

    /**
     * 应用镜像过滤
     *
     * @param imageBytes 镜像bytes
     * @param ops ops
     * @param path 路径
     * @param ext ext
     * @return apply镜像过滤器的结果
     */
    protected byte[] applyImageFilter(byte[] imageBytes, FileOperationSetting ops, String path, String ext) throws Exception {
        if (imageOperation == null) {
            log.warn("[FileStorageFilter] 未找到 ImageOperation SPI，跳过滤镜");
            return imageBytes;
        }
        if (filterSetting != null && filterSetting.isExcluded(path, ext)) {
            log.debug("[FileStorageFilter] 跳过滤镜: path={}, ext={}", path, ext);
            return imageBytes;
        }

        String format = (ops != null && ops.getFormat() != null) ? ops.getFormat() : guessFormat(ext);
        if (format == null) {
            return imageBytes;
        }

        if (ops != null && ops.getSize() != null && !ops.getSize().isBlank()) {
            String size = ops.getSize();
            Integer width = null, height = null;
            Double scale = null;
            if (size.endsWith("%")) {
                scale = Double.parseDouble(size.substring(0, size.length() - 1)) / 100.0;
            } else {
                String[] parts = size.split("[x ]");
                if (parts.length >= 2) {
                    width = parseInt(parts[0]);
                    height = parseInt(parts[1]);
                }
            }
            if (width != null || height != null || scale != null) {
                imageBytes = imageOperation.resize(imageBytes, format, width, height, scale).block();
            }
        }

        return imageBytes;
    }

    /**
     * Guess格式化
     *
     * @param ext ext
     * @return guess格式化的结果
     */
    private String guessFormat(String ext) {
        if (ext == null) {
            return "jpg";
        }
        return switch (ext) {
            case "png" -> "png";
            case "webp" -> "webp";
            case "jpg", "jpeg" -> "jpg";
            case "gif" -> "gif";
            default -> "jpg";
        };
    }

    /**
     * 解析Int
     *
     * @param s s
     * @return 解析int的结果
     */
    private Integer parseInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取flash服务
     *
     * @return 获取flash服务的结果
     */
    protected FlashTokenService getFlashService() {
        if (flashService == null) {
            Path flashDir = Path.of(setting.getCache().getFlashDir());
            flashService = new FlashTokenService(flashDir, setting.getCache().getFlashExpireSeconds());
        }
        return flashService;
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 80;
    }

    @Override
    /** 支持路径 */
    public String supportPath() {
        return "/**";
    }

    /**
     * 设置storage映射
     *
     * @param map 映射
     */
    public void setStorageMap(Map<String, FileStorage> map) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("FileStorageMap must not be empty");
        }
        this.storageMap.putAll(map);
    }

    /**
     * 从请求路径中解析文件路径（不含 bucket）。
     * 格式：/{bucket}/{filepath}
     *
     * @param request 服务端请求
     * @return filepath 部分，若无法解析返回 空
     */
    protected static String resolveFilepath(ServerRequest request) {
        String path = request.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        path = path.startsWith("/") ? path.substring(1) : path;
        int slashIndex = path.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        return path.substring(slashIndex + 1);
    }

    /**
     * 从请求路径中解析 bucket 名称。
     * 格式：/{bucket}/{filepath}
     *
     * @param request 服务端请求
     * @return bucket 名称，若无法解析返回 "默认"
     */
    protected static String resolveBucket(ServerRequest request) {
        String path = request.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "default";
        }
        path = path.startsWith("/") ? path.substring(1) : path;
        int slashIndex = path.indexOf('/');
        if (slashIndex < 0) {
            return path;
        }
        String bucket = path.substring(0, slashIndex);
        return bucket.isEmpty() ? "default" : bucket;
    }

    /**
     * 关闭过滤器，释放后台资源（PDF 缓存调度线程等）。
     *
     * <p>由 JVM shutdown hook 自动调用，也可手动调用。</p>
     */
    public void close() {
        if (pdfCache != null) {
            ALL_CACHES.remove(pdfCache);
            pdfCache.close();
        }
    }
}
