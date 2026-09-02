package com.chua.image.support.heif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;

/**
 * HEIC/HEIF ImageIO 注册入口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HeifImageIoRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HeifImageIoRegistrar.class);

    private HeifImageIoRegistrar() {}

    public static void register() {
        try {
            HeifLibraryLoader.load();
            log.info("[HeifImageIo] SPI registered, native={}", HeifLibraryLoader.isLoaded());
        } catch (Throwable e) {
            log.warn("[HeifImageIo] native not available: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        try {
            return ImageIO.getImageReadersByFormatName("heic").hasNext()
                    && ImageIO.getImageWritersByFormatName("heic").hasNext();
        } catch (Exception e) {
            return false;
        }
    }
}
