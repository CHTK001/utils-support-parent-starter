package com.chua.image.support.heif;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

import java.nio.file.Path;

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
public final class HeifLibraryLoader {

    private static final String LIBRARY_NAME = "chua_native_heif";
    private static volatile boolean loaded = false;

    private HeifLibraryLoader() {
    }

    /**
     * 加载原生库。线程安全，可重复调用。
     */
    public static synchronized void load() {
        if (loaded) return;
        try {
            Path targetDir = NativeUtils.tempRoot().resolve(LIBRARY_NAME);
            NativeLoader.of(LIBRARY_NAME)
                    .toTarget(targetDir)
                    .glob("chua_native_heif*")
                    .load();
            loaded = true;
            System.out.println("[HeifLibraryLoader] HEIC native 库加载成功");
        } catch (Throwable e) {
            System.out.println("[HeifLibraryLoader] HEIC native 库未找到或加载失败: " + e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
