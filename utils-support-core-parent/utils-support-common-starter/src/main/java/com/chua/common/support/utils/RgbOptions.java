package com.chua.common.support.utils;

/**
* 获取像素数组选项。
*
* @param image 源图片
* @param x     起始横坐标
* @param y     起始纵坐标
* @param width 宽度
* @param height 高度
* @param pixels 用于存储像素的数组（可为 空）
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
