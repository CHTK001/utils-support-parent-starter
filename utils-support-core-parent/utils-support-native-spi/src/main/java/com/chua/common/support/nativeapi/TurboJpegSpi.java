package com.chua.common.support.nativeapi;

import java.util.Objects;

/**
 * libjpeg-turbo 原生库 SPI。
 *
 * <p>由 {@code utils-support-native-libjpeg-turbo} 提供实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface TurboJpegSpi extends NativeService {

    /**
     * 像素格式：24 位 RGB。
     */
    int TJPF_RGB = 0;

    /**
     * 像素格式：8 位灰度。
     */
    int TJPF_GRAY = 6;

    /**
     * 色度二次采样：4:2:0。
     */
    int TJSAMP_420 = 2;

    /**
     * 色度二次采样：单通道灰度。
     */
    int TJSAMP_GRAY = 3;

    /**
     * 解码结果。
     *
     * @param pixels 打包像素字节
     * @param width  宽
     * @param height 高
     * @param pitch  行跨距
     */
    record Decoded(byte[] pixels, int width, int height, int pitch) {

        /**
         * 规范构造器：像素缓冲做防御性拷贝。
         */
        public Decoded {
            pixels = Objects.requireNonNull(pixels, "pixels 不能为 null").clone();
        }

        /**
         * 访问器覆写：返回像素缓冲副本。
         *
         * @return 像素字节副本
         */
        @Override
        public byte[] pixels() {
            return pixels.clone();
        }
    }

    /**
     * 加载原生库。
     */
    void load();

    /**
     * 原生库是否已加载。
     *
     * @return 已加载返回 {@code true}
     */
    boolean isLoaded();

    @Override
    default boolean available() {
        if (isLoaded()) {
            return true;
        }
        load();
        return isLoaded();
    }

    /**
     * 原生库加载失败原因。
     *
     * @return 失败异常；加载成功返回 {@code null}
     */
    Throwable getLoadError();

    /**
     * 内嵌的 libjpeg-turbo 版本号。
     *
     * @return 版本串
     */
    String version();

    /**
     * 探测 JPEG 头部。
     *
     * @param jpeg JPEG 字节
     * @return 长度为 3 的数组：宽 / 高 / 色度二次采样
     */
    int[] probe(byte[] jpeg);

    /**
     * 压缩为 JPEG。
     *
     * @param pixels      打包像素缓冲
     * @param width       宽
     * @param height      高
     * @param pitch       行跨距
     * @param pixelFormat 像素格式
     * @param quality     质量
     * @param subsamp     色度二次采样
     * @param flags       标志位
     * @return JPEG 字节
     */
    byte[] compress(byte[] pixels, int width, int height, int pitch, int pixelFormat,
                    int quality, int subsamp, int flags);

    /**
     * 解码为打包像素缓冲。
     *
     * @param jpeg        JPEG 字节
     * @param pixelFormat 目标像素格式
     * @param flags       标志位
     * @return 解码结果
     */
    Decoded decompress(byte[] jpeg, int pixelFormat, int flags);
}
