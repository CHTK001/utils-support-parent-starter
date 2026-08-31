package com.chua.image.support.heif;

import com.github.gotson.nightmonkeys.heif.imageio.plugins.HeifImageReaderSpi;
import com.github.gotson.nightmonkeys.heif.imageio.plugins.HeifImageWriterSpi;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * HEIC/HEIF ImageIO SPI 注册工具。
 *
 * <p>注册 {@link HeifImageReaderSpi} 和 {@link HeifImageWriterSpi} 到 {@link IIORegistry}，
 * 使 {@link ImageIO} 能够读取和写入 HEIC/HEIF 格式图片。</p>
 *
 * <p>依赖：{@code com.github.gotson.nightmonkeys:imageio-heif}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class HeifImageIoRegistrar {

    private static volatile boolean registered = false;

    private HeifImageIoRegistrar() {
    }

    /**
     * 注册 HEIC/HEIF ImageReader/ImageWriter SPI 到 ImageIO 注册表。
     * 该方法线程安全，可多次调用。
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            IIORegistry registry = IIORegistry.getDefaultInstance();
            registry.registerService(new HeifImageReaderSpi(), HeifImageReaderSpi.class, true);
            registry.registerService(new HeifImageWriterSpi(), HeifImageWriterSpi.class, true);
            registered = true;
            log.info("[HeifImageIo] HEIC/HEIF ImageIO SPI 注册成功");
        } catch (Exception e) {
            log.warn("[HeifImageIo] HEIC/HEIF ImageIO SPI 注册失败: {}", e.getMessage());
        }
    }

    /**
     * 判断当前 JVM 是否已支持 HEIC/HEIF 图片读写。
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
