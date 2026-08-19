package com.chua.filestorage.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.bridge.RustFileStorageBridge;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Rust 实现的图片全局滤镜设置。
 *
 * <p>优先使用 Rust native 库提供滤镜能力。
 * 若 Rust 库未初始化，则自动降级到 JDK 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("native")
public class NativeFileStorageFilterSetting implements FileStorageFilterSetting {

    @Override
    /** Capabilities */
    public List<String> capabilities() {
        if (!RustFileStorageBridge.isInitialized()) {
            log.debug("[FileStorageFilterSetting] Rust 库未初始化，降级使用 JDK 能力列表");
            return new JdkFileStorageFilterSetting().capabilities();
        }
        return RustFileStorageBridge.nativeFilterCapabilities();
    }

    @Override
    /** 获取过滤Chain */
    public List<ImageFilterConfig> getFilterChain() {
        if (!RustFileStorageBridge.isInitialized()) {
            log.debug("[FileStorageFilterSetting] Rust 库未初始化，降级使用 JDK 滤镜链");
            return new JdkFileStorageFilterSetting().getFilterChain();
        }
        return RustFileStorageBridge.nativeGetFilterChain();
    }

    @Override
    /** 是否Excluded */
    public boolean isExcluded(String path, String extension) {
        if (!RustFileStorageBridge.isInitialized()) {
            return new JdkFileStorageFilterSetting().isExcluded(path, extension);
        }
        return RustFileStorageBridge.nativeIsExcluded(path, extension);
    }
}
