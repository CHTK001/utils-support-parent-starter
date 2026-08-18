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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件存储服务器过滤器（抽象基类）。
 *
 * <p>聚合 FileStorage 实例，提供预览、下载、图片滤镜、闪图等功能。
 * 支持热重载（upgrade）更新 fileSetting / filterSetting / imageOperation。</p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Slf4j
public abstract class AbstractFileStorageServerFilter implements ServerFilter {

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
    protected transient FlashTokenService flashService;

    public AbstractFileStorageServerFilter(FileStorageSetting setting) {
        this(setting, null);
    }

    public AbstractFileStorageServerFilter(FileStorageSetting setting, PreviewPdfCache pdfCache) {
        this.setting = setting;
        this.pdfCache = pdfCache != null ? pdfCache : new PreviewPdfCache(Path.of(setting.getCache().getPdfCacheDir()));
        loadSpis();
    }

    private void loadSpis() {
        String fsKey = StringUtils.isEmpty(setting.getFileSettingKey()) ? "jdk" : setting.getFileSettingKey();
        this.fileSetting = ServiceProvider.of(FileStorageFileSetting.class).getNewExtension(fsKey);

        String filterKey = StringUtils.isEmpty(setting.getFilterSettingKey()) ? "jdk" : setting.getFilterSettingKey();
        this.filterSetting = ServiceProvider.of(FileStorageFilterSetting.class).getNewExtension(filterKey);

        String imgOpKey = StringUtils.isEmpty(setting.getImageFilterKey()) ? null : setting.getImageFilterKey();
        this.imageOperation = ServiceProvider.of(ImageOperation.class).getExtension(imgOpKey);
    }

    public void addFileStorage(String name, FileStorage storage) {
        storageMap.put(name, storage);
    }

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

    PreviewPdfCache getPdfCache() {
        return pdfCache;
    }

    /**
     * 热重载配置（更新 fileSetting 和 filterSetting）。
     *
     * @param fileSetting 新的 FileStorageFileSetting 实例（传 null 保留原值）
     * @param filterSetting 新的 FileStorageFilterSetting 实例（传 null 保留原值）
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

    private Integer parseInt(String s) {
        try { return Integer.valueOf(s); } catch (Exception e) { return null; }
    }

    protected FlashTokenService getFlashService() {
        if (flashService == null) {
            Path flashDir = Path.of(setting.getCache().getFlashDir());
            flashService = new FlashTokenService(flashDir, setting.getCache().getFlashExpireSeconds());
        }
        return flashService;
    }

    @Override
    public int getOrder() {
        return 80;
    }

    @Override
    public String supportPath() {
        return "/**";
    }

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
     * @return filepath 部分，若无法解析返回 null
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
     * @return bucket 名称，若无法解析返回 "default"
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
}
