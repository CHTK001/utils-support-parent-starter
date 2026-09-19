package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 明亮度增强图像滤镜
 *
 * 通过增加RGB各个颜色通道的数值来提高图像的整体亮度。
 * 该滤镜对图像的每个像素点进行亮度调整，使图像看起来更加明亮。
 *
 * 技术原理：
 * - 提取每个像素的RGB分量
 * - 对每个颜色通道增加固定数值（默认+10）
 * - 确保颜色值不超过255的上限
 * - 重新组合RGB值形成新的像素
 *
 * 应用场景：
 * - 照片后期处理：提升暗淡照片的亮度
 * - 图像增强：改善低光照条件下拍摄的图像
 * - 显示优化：为不同显示设备调整图像亮度
 * - 艺术效果：创建明亮、清新的视觉效果
 *
 * 注意事项：
 * - 过度增亮可能导致图像过曝
 * - 建议配合对比度调整使用
 * - 对于已经很亮的图像效果有限
 *
 * @author CH
 * @版本 1.0.0
 * @since 4.0.0.42
 */
@Spi("Bright")
@SpiDescribe("明亮度增强滤镜")
public class ImageBrightImageFilter extends AbstractImageFilter {

    /**
     * 默认亮度增加值
     */
    private static final int DEFAULT_BRIGHTNESS_INCREASE = 10;

    /**
     * 执行明亮度增强滤镜处理
     *
     * 对图像的每个像素进行亮度增强处理，通过增加RGB各通道的数值
     * 来提高图像的整体亮度。处理过程中会确保颜色值不会溢出。
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用，方法会创建新图像）
     * @return 亮度增强后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
 // 注意：这里应该使用类型_INT_RGB而不是类型_BYTE_GRAY，因为我们要保持彩色
        BufferedImage brightImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);

        int width = src.getWidth();
        int height = src.getHeight();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelVal = src.getRGB(x, y);

                // 提取RGB分量
                int red = (pixelVal >> 16) & 0xFF;
                int green = (pixelVal >> 8) & 0xFF;
                int blue = pixelVal & 0xFF;

                // 增加亮度，确保不超过255
                red = Math.min(255, red + DEFAULT_BRIGHTNESS_INCREASE);
                green = Math.min(255, green + DEFAULT_BRIGHTNESS_INCREASE);
                blue = Math.min(255, blue + DEFAULT_BRIGHTNESS_INCREASE);

                // 重新组合RGB值
                Color brightColor = new Color(red, green, blue);
                brightImage.setRGB(x, y, brightColor.getRGB());
            }
        }

        return brightImage;
    }
}
