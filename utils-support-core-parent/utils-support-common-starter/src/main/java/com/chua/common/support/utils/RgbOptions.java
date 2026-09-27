package com.chua.common.support.utils;

/**
 * 获取像素数组选项。
 *
 * <p><b>例外：{@code pixels} 刻意不做防御性拷贝</b>，不满足 value class 的深不可变要求。
 * 它是<b>出参缓冲区</b>——{@code BufferedImageUtils.getRgb} 会把它原样交给
 * {@code image.getRaster().getDataElements(x, y, w, h, pixels)} 与
 * {@code image.getRGB(x, y, w, h, pixels, 0, w)}，由 AWT 就地写入像素；
 * 拷贝后写入的是副本，返回值仍指向副本缓冲区，原缓冲区永远为空，行为即被破坏。
 * 这与 {@code WechatMemoryExtractor.IdCluster} 同属「算法要求的活引用」类例外。</p>
 *
 * @param image 源图片
 * @param x     起始横坐标
 * @param y     起始纵坐标
 * @param width 宽度
 * @param height 高度
 * @param pixels 用于存储像素的数组（可为 null，由 AWT 自行分配并返回）
 * @author CH
 * @since 4.0.0.42
 */
public record RgbOptions(
        java.awt.image.BufferedImage image,
        int x,
        int y,
        int width,
        int height,
        int[] pixels
) {
}
