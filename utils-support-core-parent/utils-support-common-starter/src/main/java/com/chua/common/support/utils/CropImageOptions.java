package com.chua.common.support.utils;

/**
 * 图片裁剪选项。
 *
 * @param image   源图片
 * @param x       起始横坐标
 * @param y       起始纵坐标
 * @param width   裁剪宽度
 * @param height  裁剪高度
 * @author CH
 * @since 4.0.0.42
*/
public record CropImageOptions(
        java.awt.image.BufferedImage image,
        int x,
        int y,
        int width,
        int height
) {
}
