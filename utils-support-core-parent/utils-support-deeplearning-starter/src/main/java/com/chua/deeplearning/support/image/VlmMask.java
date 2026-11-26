package com.chua.deeplearning.support.image;

/**
 * 分割/抠图返回的 alpha 掩码。
 *
 * <p>掩码以 8 位灰度 PNG 承载（像素值为 0..255 的 alpha），而不是 {@code float[]}：
 * 原图尺寸的概率图按 float 传输会放大 4 倍以上，HTTP 与内存都吃不消；
 * PNG 无损压缩后体积可控，调用方可直接解码成图像使用。
 *
 * @param width    掩码宽度（等于原图宽度）
 * @param height   掩码高度（等于原图高度）
 * @param alphaPng 8 位灰度 PNG 字节
 * @author CH
 * @since 4.0.0.51
 */
public record VlmMask(int width, int height, byte[] alphaPng) {

    /**
     * 规范构造器：校验尺寸并对字节数组做防御性拷贝。
     *
     * @param width    掩码宽度
     * @param height   掩码高度
     * @param alphaPng 8 位灰度 PNG 字节
     */
    public VlmMask {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("掩码宽高必须大于 0: " + width + "x" + height);
        }
        if (alphaPng == null || alphaPng.length == 0) {
            throw new IllegalArgumentException("掩码数据不能为空");
        }
        alphaPng = alphaPng.clone();
    }

    /**
     * 获取掩码字节的副本（record 自动生成的访问器会直接暴露内部数组，此处覆写为拷贝）。
     *
     * @return PNG 字节副本
     */
    @Override
    public byte[] alphaPng() {
        return alphaPng.clone();
    }
}