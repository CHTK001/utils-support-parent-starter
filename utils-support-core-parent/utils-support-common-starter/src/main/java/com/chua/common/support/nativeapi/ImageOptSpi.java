package com.chua.common.support.nativeapi;

/**
 * 图片压缩原生库 SPI（libdeflate + libimagequant）。
 *
 * <p>由 {@code utils-support-native-imageopt} 提供实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ImageOptSpi extends NativeService {

    /**
     * 量化结果。
     *
     * @param palette 每色 4 字节 RGBA
     * @param colors  实际颜色数
     * @param indices 每像素一个索引
     */
    record Quantized(byte[] palette, int colors, byte[] indices) {

        /**
         * 规范构造器：数组做防御性拷贝。
         */
        public Quantized {
            palette = palette == null ? new byte[0] : palette.clone();
            indices = indices == null ? new byte[0] : indices.clone();
        }

        /**
         * 访问器覆写：返回调色板副本。
         *
         * @return 调色板字节副本
         */
        @Override
        public byte[] palette() {
            return palette.clone();
        }

        /**
         * 访问器覆写：返回索引副本。
         *
         * @return 索引字节副本
         */
        @Override
        public byte[] indices() {
            return indices.clone();
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
     * 最近一次原生调用失败的原因描述。
     *
     * @return 错误描述
     */
    String lastError();

    /**
     * 单次 zlib 压缩。
     *
     * @param data  待压缩数据
     * @param level 压缩级别 0~12
     * @return 带 zlib 头的压缩字节
     */
    byte[] zlibCompress(byte[] data, int level);

    /**
     * 多档试压取最小值的 zlib 压缩。
     *
     * @param data     待压缩数据
     * @param level    目标压缩级别 0~12
     * @param attempts 试压档数
     * @return 最短压缩结果
     */
    byte[] zlibCompressBest(byte[] data, int level, int attempts);

    /**
     * 调色板量化。
     *
     * @param rgba      每像素 4 字节 RGBA
     * @param width     宽
     * @param height    高
     * @param maxColors 目标色数 2~256
     * @param dither    抖动强度 0~100
     * @param speed     速度档 1~10
     * @return 量化结果
     */
    Quantized quantize(byte[] rgba, int width, int height, int maxColors, int dither, int speed);
}
