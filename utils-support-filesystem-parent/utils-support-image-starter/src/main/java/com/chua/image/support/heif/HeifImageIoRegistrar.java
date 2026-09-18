package com.chua.image.support.heif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;

/**
* HEIC/HEIF 镜像io 注册入口。
*
* @author CH
* @since 4.0.0.42
* @return 是否可用的结果
 */
public final class HeifImageIoRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HeifImageIoRegistrar.class); // 日志
/**
* heif镜像ioregistrar。
 */

    private HeifImageIoRegistrar() {}
/**
* 注册。
* @return 是否可用的结果
 */

    public static void register() {
        try {
            HeifLibraryLoader.load();
            log.info("[HeifImageIo] SPI registered, native={}", HeifLibraryLoader.isLoaded());
        } catch (Throwable e) {
            log.warn("[HeifImageIo] native not available: {}", e.getMessage());
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
