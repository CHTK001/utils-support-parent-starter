package com.chua.common.support.utils;

import java.awt.*;

/**
* 文字水印选项。
*
* @param image 源图片
* @param text  水印文字
* @param color 文字颜色（可为 空，默认灰色）
* @param font  字体（可为 空，默认 32 号无衬线字体）
* @param x     水印横坐标
* @param y     水印纵坐标
* @param alpha 透明度（0-1，0 完全透明，1 完全不透明）
* @author CH
* @since 4.0.0.42
 */
public record TextWatermarkOptions(
        java.awt.image.BufferedImage image,
        String text,
        Color color,
        Font font,
        int x,
        int y,
        float alpha
) {
}
