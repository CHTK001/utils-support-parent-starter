package com.chua.image.support.heif;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
* HEIC/HEIF 原生库加载器。
*
* <p>使用 {@link NativeLoader} 从 classpath {@code native/{platform}/} 抽取并加载
* {@code chua_native_heif} 动态库。支持 Windows(.dll)、Linux(.so)、macOS(.dylib)。</p>
*
* <p>若原生库不存在或加载失败，SPI 仍可通过 pure-Java 路径工作（JPEG-based HEIF）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class HeifLibraryLoader {

    private static final String LIBRARY_NAME = "chua_native_heif"; // 图书馆名称
    private static volatile boolean loaded = false; // 加载

    /**
    * heif图书馆加载。
     */
    private HeifLibraryLoader() {
    }

    /**
    * 加载原生库。线程安全，可重复调用。
    */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            Path targetDir = NativeUtils.tempRoot().resolve(LIBRARY_NAME);
            NativeLoader.of(LIBRARY_NAME)
                    .toTarget(targetDir)
                    .glob("chua_native_heif*")
                    .load();
            loaded = true;
            log.info("[HeifLibraryLoader] HEIC native 库加载成功");
        } catch (Throwable e) {
            log.warn("[HeifLibraryLoader] HEIC native 库未找到或加载失败: {}", e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
