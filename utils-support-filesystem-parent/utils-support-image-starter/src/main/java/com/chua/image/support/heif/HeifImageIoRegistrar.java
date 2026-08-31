package com.chua.image.support.heif;

import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * HEIC/HEIF ImageIO 注册入口。
 *
 * <p>注册自定义 {@code com.chua} SPI（不依赖 nightmonkeys/imageio-heif），
 * 同时尝试加载 Rust 原生加速库。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class HeifImageIoRegistrar {

    private HeifImageIoRegistrar() {
    }

    /**
     * 注册 SPI 并尝试加载原生加速库。
     */
    public static void register() {
        try {
            HeifLibraryLoader.load();
            log.info("[HeifImageIo] HEIC/HEIF SPI 已注册，native={}", HeifLibraryLoader.isLoaded());
        } catch (Throwable e) {
            log.debug("[HeifImageIo] 原生库未加载，使用 pure-Java 路径: {}", e.getMessage());
        }
    }

    /**
     * 判断当前 JVM 是否支持 HEIC/HEIF。
     */
    public static boolean isAvailable() {
        try {
            return ImageIO.getImageReadersByFormatName("heic").hasNext()
                    && ImageIO.getImageWritersByFormatName("heic").hasNext();
        } catch (Exception e) {
            return false;
        }
    }
}
