package com.chua.image.support.heif;

import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * HEIC/HEIF ImageIO 注册入口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HeifImageIoRegistrar {

    private HeifImageIoRegistrar() {}

    public static void register() {
        try {
            HeifLibraryLoader.load();
            System.out.println("[HeifImageIo] SPI registered, native=" + HeifLibraryLoader.isLoaded());
        } catch (Throwable e) {
            System.out.println("[HeifImageIo] native not available: " + e.getMessage());
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
