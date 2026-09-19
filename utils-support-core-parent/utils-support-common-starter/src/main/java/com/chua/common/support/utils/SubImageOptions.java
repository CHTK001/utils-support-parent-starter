package com.chua.common.support.utils;

/**
 * 获取子图选项。
 *
 * @param bufferedImage 源图片
 * @param x             起始横坐标
 * @param y             起始纵坐标
 * @param width         子图宽度
 * @param height        子图高度
 * @author CH
 * @since 4.0.0.42
 */
public record SubImageOptions(
        java.awt.image.BufferedImage bufferedImage,
        int x,
        int y,
        int width,
        int height
) {
}
