package com.chua.image.support.heif;


import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * HEIC/HEIF 镜像io 注册入口。
 *
 * @author CH
 * @since 4.0.0.42
 * @return 是否可用的结果
 */
@Slf4j
public final class HeifImageIoRegistrar {

/**
 * heif镜像ioregistrar。
 */

    private HeifImageIoRegistrar() {}
/**
 * 注册。
 */

    public static void register() {
        try {
            HeifLibraryLoader.load();
            log.info("[HeifImageIo] SPI registered, native={}", HeifLibraryLoader.isLoaded());
        } catch (Throwable e) {
            log.warn("[HeifImageIo] native not available: {}", e.getMessage(), e);
        }
    }

    /**
     * 是否Available。
     *
     * @return 是否成功（true 表示成功）
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
